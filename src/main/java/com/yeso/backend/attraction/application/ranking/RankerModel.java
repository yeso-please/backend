package com.yeso.backend.attraction.application.ranking;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LightGBM 순위 모델(ai `tripin_ai/ranking/export.py`가 내보낸 JSON). 네이티브 라이브러리 없이 트리를 직접 따라간다.
 *
 * <p>점수 = 모든 트리 잎 값의 합(LightGBM {@code predict(raw_score=True)}). 수치 분할은 {@code x <= t}이면 왼쪽,
 * 범주 분할은 코드가 목록에 있으면 왼쪽이고 음수(모르는 값)는 오른쪽이다. 결측(NaN·0)은 노드의 기본 방향을 따른다.
 */
public final class RankerModel {

    private static final double ZERO_THRESHOLD = 1e-35;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String version;
    private final List<String> features;
    private final Map<String, Map<String, Integer>> categorical;
    private final double[] pcaMean;
    private final double[][] pcaComponents;
    private final String e5ModelVersion;
    private final Node[][] trees;
    // 빠른 순회용 평면 배열(트리별): 분할 특성(-1이면 잎), 임계값/잎 값, 왼쪽·오른쪽 자식
    private final int[][] featureOf;
    private final double[][] thresholdOrValue;
    private final int[][] leftOf;
    private final int[][] rightOf;
    private final boolean[][] simple;   // 범주·결측 처리가 필요 없는 수치 분할

    private record Node(int feature, double threshold, int[] categories, boolean defaultLeft, char missing,
                        int left, int right, double value, boolean leaf) {
    }

    private RankerModel(JsonNode root) {
        this.version = root.path("version").asString();
        this.e5ModelVersion = root.path("e5ModelVersion").asString("");
        List<String> names = new ArrayList<>();
        root.path("features").forEach(name -> names.add(name.asString()));
        this.features = List.copyOf(names);
        Map<String, Map<String, Integer>> vocab = new HashMap<>();
        root.path("categorical").properties().forEach(field -> {
            Map<String, Integer> codes = new HashMap<>();
            field.getValue().properties().forEach(entry -> codes.put(entry.getKey(), entry.getValue().asInt()));
            vocab.put(field.getKey(), Map.copyOf(codes));
        });
        this.categorical = Map.copyOf(vocab);
        JsonNode pca = root.path("pca");
        if (pca.isMissingNode() || pca.isNull()) {
            this.pcaMean = new double[0];
            this.pcaComponents = new double[0][];
        } else {
            this.pcaMean = toArray(pca.path("mean"));
            List<double[]> components = new ArrayList<>();
            pca.path("components").forEach(row -> components.add(toArray(row)));
            this.pcaComponents = components.toArray(double[][]::new);
        }
        List<Node[]> parsed = new ArrayList<>();
        for (JsonNode tree : root.path("trees")) {
            List<Node> nodes = new ArrayList<>();
            for (JsonNode n : tree) {
                if (n.has("v")) {
                    nodes.add(new Node(-1, 0, null, false, '0', -1, -1, n.get("v").asDouble(), true));
                    continue;
                }
                int[] cats = null;
                if (n.has("c")) {
                    cats = new int[n.get("c").size()];
                    for (int i = 0; i < cats.length; i++) {
                        cats[i] = n.get("c").get(i).asInt();
                    }
                    Arrays.sort(cats);
                }
                char missing = switch (n.path("mt").asString("None")) {
                    case "NaN" -> 'N';
                    case "Zero" -> 'Z';
                    default -> '0';     // None
                };
                nodes.add(new Node(n.get("f").asInt(), n.path("t").asDouble(0), cats, n.path("dl").asBoolean(),
                        missing, n.get("l").asInt(), n.get("r").asInt(), 0, false));
            }
            parsed.add(nodes.toArray(Node[]::new));
        }
        this.trees = parsed.toArray(Node[][]::new);
        this.featureOf = new int[trees.length][];
        this.thresholdOrValue = new double[trees.length][];
        this.leftOf = new int[trees.length][];
        this.rightOf = new int[trees.length][];
        this.simple = new boolean[trees.length][];
        for (int t = 0; t < trees.length; t++) {
            Node[] tree = trees[t];
            featureOf[t] = new int[tree.length];
            thresholdOrValue[t] = new double[tree.length];
            leftOf[t] = new int[tree.length];
            rightOf[t] = new int[tree.length];
            simple[t] = new boolean[tree.length];
            for (int i = 0; i < tree.length; i++) {
                Node node = tree[i];
                featureOf[t][i] = node.leaf() ? -1 : node.feature();
                thresholdOrValue[t][i] = node.leaf() ? node.value() : node.threshold();
                leftOf[t][i] = node.left();
                rightOf[t][i] = node.right();
                simple[t][i] = !node.leaf() && node.categories() == null && node.missing() == '0';
            }
        }
        if (trees.length == 0 || features.isEmpty()) {
            throw new IllegalArgumentException("Ranker model has no trees or features");
        }
    }

    public static RankerModel read(InputStream input) throws IOException {
        return new RankerModel(JSON.readTree(input));
    }

    private static double[] toArray(JsonNode array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).asDouble();
        }
        return values;
    }

    public String version() {
        return version;
    }

    public String e5ModelVersion() {
        return e5ModelVersion;
    }

    public List<String> features() {
        return features;
    }

    /** 범주 값의 학습 코드. 학습에 없던 값이면 -1(트리에서 오른쪽으로 간다). */
    public int categoryCode(String field, String value) {
        Map<String, Integer> codes = categorical.get(field);
        if (codes == null) {
            return -1;
        }
        return codes.getOrDefault(value == null ? "" : value, -1);
    }

    /** 관광지 벡터를 학습 때와 같은 PCA로 줄인다. PCA가 없는 모델이면 빈 배열. */
    public double[] project(float[] vector) {
        double[] out = new double[pcaComponents.length];
        if (pcaComponents.length == 0) {
            return out;
        }
        if (vector.length != pcaMean.length) {
            throw new IllegalArgumentException("Vector dimension " + vector.length + " != PCA " + pcaMean.length);
        }
        for (int k = 0; k < pcaComponents.length; k++) {
            double sum = 0;
            double[] component = pcaComponents[k];
            for (int i = 0; i < vector.length; i++) {
                sum += (vector[i] - pcaMean[i]) * component[i];
            }
            out[k] = sum;
        }
        return out;
    }

    /** 특성 순서는 {@link #features()}와 같아야 한다. */
    public double score(double[] x) {
        if (x.length != features.size()) {
            throw new IllegalArgumentException("Expected " + features.size() + " features, got " + x.length);
        }
        double total = 0;
        for (int t = 0; t < featureOf.length; t++) {
            int[] features = featureOf[t];
            double[] values = thresholdOrValue[t];
            int index = 0;
            while (features[index] >= 0) {
                double value = x[features[index]];
                boolean left = simple[t][index] && !Double.isNaN(value)
                        ? value <= values[index] : goesLeft(trees[t][index], value);
                index = left ? leftOf[t][index] : rightOf[t][index];
            }
            total += values[index];
        }
        return total;
    }

    private static boolean goesLeft(Node node, double value) {
        if (Double.isNaN(value)) {
            if (node.categories() != null) {
                return false;
            }
            if (node.missing() == 'N') {
                return node.defaultLeft();
            }
            value = 0;   // missing_type None·Zero: LightGBM은 NaN을 0으로 본다
        }
        if (node.categories() != null) {
            int code = (int) value;
            return code >= 0 && Arrays.binarySearch(node.categories(), code) >= 0;
        }
        if (node.missing() == 'Z' && Math.abs(value) <= ZERO_THRESHOLD) {
            return node.defaultLeft();
        }
        return value <= node.threshold();
    }
}
