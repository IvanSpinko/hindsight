package org.example.visiontrack.render;

import org.example.visiontrack.analysis.AnalysisResult.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedVideoRendererTest {

    private static final double STEP = 0.2;

    private final List<Position> track = List.of(
            new Position(1.0, 100, 100, 50, 50),
            new Position(1.2, 120, 100, 50, 50),
            new Position(2.2, 300, 100, 50, 50));

    @Test
    void interpolatesBetweenConsecutiveSightings() {
        Position p = AnnotatedVideoRenderer.positionAt(track, 1.1, STEP);
        assertThat(p.x()).isEqualTo(110);
    }

    @Test
    void hidesObjectBeforeItAppearsDuringGapsAndAfterItLeaves() {
        assertThat(AnnotatedVideoRenderer.positionAt(track, 0.5, STEP)).isNull();
        assertThat(AnnotatedVideoRenderer.positionAt(track, 1.7, STEP)).isNull();
        assertThat(AnnotatedVideoRenderer.positionAt(track, 3.0, STEP)).isNull();
    }

    @Test
    void holdsBoxBrieflyAroundSightings() {
        assertThat(AnnotatedVideoRenderer.positionAt(track, 0.9, STEP)).isEqualTo(track.get(0));
        assertThat(AnnotatedVideoRenderer.positionAt(track, 2.3, STEP)).isEqualTo(track.get(2));
    }

    @Test
    void sameLabelAlwaysGetsTheSameColour() {
        assertThat(AnnotatedVideoRenderer.colorFor("person")).isEqualTo(AnnotatedVideoRenderer.colorFor("person"));
    }
}
