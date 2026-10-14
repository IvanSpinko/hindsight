package org.example.visiontrack.detection;

public record Detection(int classId, String label, float confidence, BoundingBox box) {

    public record BoundingBox(float x1, float y1, float x2, float y2) {

        public float width() {
            return Math.max(0f, x2 - x1);
        }

        public float height() {
            return Math.max(0f, y2 - y1);
        }

        public float area() {
            return width() * height();
        }

        public float iou(BoundingBox other) {
            float ix = Math.min(x2, other.x2) - Math.max(x1, other.x1);
            float iy = Math.min(y2, other.y2) - Math.max(y1, other.y1);
            if (ix <= 0f || iy <= 0f) return 0f;
            float intersection = ix * iy;
            float union = area() + other.area() - intersection;
            return union <= 0f ? 0f : intersection / union;
        }
    }
}
