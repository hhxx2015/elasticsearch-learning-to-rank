/*
 * Copyright [2017] Wikimedia Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.o19s.es.ltr.ranker.parser;

import com.o19s.es.ltr.feature.FeatureSet;
import com.o19s.es.ltr.ranker.dectree.NaiveAdditiveDecisionTree;
import com.o19s.es.ltr.ranker.dectree.NaiveAdditiveDecisionTree.Node;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parse XGBoost models generated by mjolnir (https://gerrit.wikimedia.org/r/search/MjoLniR)
 */
public class XGBoostJsonParser implements LtrRankerParser {
    public static final String TYPE = "model/xgboost+json";

    @Override
    public NaiveAdditiveDecisionTree parse(FeatureSet set, String model) {
        List<Node> trees = new ArrayList<>();
        try (XContentParser parser = JsonXContent.jsonXContent.createParser(NamedXContentRegistry.EMPTY,
                LoggingDeprecationHandler.INSTANCE, model)
        ) {
            if (parser.nextToken() != XContentParser.Token.START_ARRAY) {
                throw new ParsingException(parser.getTokenLocation(), "Expected [START_ARRAY] but got [" + parser.currentToken() + "]");
            }
            while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                trees.add(SplitParserState.parse(parser, set).toNode(set));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot parse model", e);
        }
        float[] weights = new float[trees.size()];
        // Tree weights are already encoded in outputs
        Arrays.fill(weights, 1F);
        return new NaiveAdditiveDecisionTree(trees.toArray(new Node[0]), weights, set.size());
    }

    private static class SplitParserState {
        private static final ObjectParser<SplitParserState, FeatureSet> PARSER;
        static {
            PARSER = new ObjectParser<>("node", SplitParserState::new);
            PARSER.declareInt(SplitParserState::setNodeId, new ParseField("nodeid"));
            PARSER.declareInt(SplitParserState::setDepth, new ParseField("depth"));
            PARSER.declareString(SplitParserState::setSplit, new ParseField("split"));
            PARSER.declareFloat(SplitParserState::setThreshold, new ParseField("split_condition"));
            PARSER.declareInt(SplitParserState::setRightNodeId, new ParseField("no"));
            PARSER.declareInt(SplitParserState::setLeftNodeId, new ParseField("yes"));
            PARSER.declareInt(SplitParserState::setMissingNodeId, new ParseField("missing"));
            PARSER.declareFloat(SplitParserState::setLeaf, new ParseField("leaf"));
            PARSER.declareObjectArray(SplitParserState::setChildren, SplitParserState::parse,
                    new ParseField("children"));
            PARSER.declareFloat(SplitParserState::setThreshold, new ParseField("split_condition"));
        }

        private Integer nodeId;
        private Integer depth;
        private String split;
        private Float threshold;
        private Integer rightNodeId;
        private Integer leftNodeId;
        // Ignored
        private Integer missingNodeId;
        private Float leaf;
        private List<SplitParserState> children;

        public static SplitParserState parse(XContentParser parser, FeatureSet set) {
            SplitParserState split = PARSER.apply(parser, set);
            if (split.isSplit()) {
                if (!split.splitHasAllFields()) {
                    throw new ParsingException(parser.getTokenLocation(), "This split does not have all the required fields");
                }
                if (!split.splitHasValidChildren()) {
                    throw new ParsingException(parser.getTokenLocation(), "Split structure is invalid, yes, no and/or" +
                            " missing branches does not point to the proper children.");
                }
                if (!set.hasFeature(split.split)) {
                    throw new ParsingException(parser.getTokenLocation(), "Unknown feature [" + split.split + "]");
                }
            } else if (!split.leafHasAllFields()) {
                throw new ParsingException(parser.getTokenLocation(), "This leaf does not have all the required fields");
            }
            return split;
        }
        void setNodeId(Integer nodeId) {
            this.nodeId = nodeId;
        }

        void setDepth(Integer depth) {
            this.depth = depth;
        }

        void setSplit(String split) {
            this.split = split;
        }

        void setThreshold(Float threshold) {
            this.threshold = threshold;
        }

        void setRightNodeId(Integer rightNodeId) {
            this.rightNodeId = rightNodeId;
        }

        void setLeftNodeId(Integer leftNodeId) {
            this.leftNodeId = leftNodeId;
        }

        void setMissingNodeId(Integer missingNodeId) {
            this.missingNodeId = missingNodeId;
        }

        void setLeaf(Float leaf) {
            this.leaf = leaf;
        }

        void setChildren(List<SplitParserState> children) {
            this.children = children;
        }

        boolean splitHasAllFields() {
            return nodeId != null && threshold != null && split != null && leftNodeId != null && rightNodeId != null && depth != null
                    && children != null && children.size() == 2;
        }

        boolean leafHasAllFields() {
            return nodeId != null && leaf != null;
        }

        boolean splitHasValidChildren() {
            return children.size() == 2 &&
                    leftNodeId.equals(children.get(0).nodeId) && rightNodeId.equals(children.get(1).nodeId);
        }
        boolean isSplit() {
            return leaf == null;
        }


        Node toNode(FeatureSet set) {
            if (isSplit()) {
                return new NaiveAdditiveDecisionTree.Split(children.get(0).toNode(set), children.get(1).toNode(set),
                        set.featureOrdinal(split), threshold);
            } else {
                return new NaiveAdditiveDecisionTree.Leaf(leaf);
            }
        }
    }
}
