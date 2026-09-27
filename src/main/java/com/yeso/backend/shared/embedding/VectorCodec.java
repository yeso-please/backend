package com.yeso.backend.shared.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 임베딩 벡터 바이트 형식: float32 리틀엔디언(PyTorch/NumPy {@code tobytes()} 그대로).
 * 임베딩 서비스(yeso-please/ai)와의 계약이며 DB({@code user_taste_vectors}, {@code attraction_embeddings})도 이 바이트로 저장한다
 * (docs/design/recommendation.md §5).
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("float32 벡터 바이트가 아닙니다: " + (bytes == null ? null : bytes.length));
        }
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }

    /** 바이트가 float32 {@code dimension}개가 아니면 빈 값. 깨진 벡터 하나로 요청 전체가 실패하지 않게 한다. */
    public static java.util.Optional<float[]> tryDecode(byte[] bytes, int dimension) {
        if (bytes == null || bytes.length != dimension * Float.BYTES) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(decode(bytes));
    }

    public static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }
}
