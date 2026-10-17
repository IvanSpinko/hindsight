package org.example.visiontrack.meeting;

import org.example.visiontrack.analysis.ProgressListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns an uploaded recording (a Zoom or OBS file, a voice memo) into the tracks of a meeting: the sound for speech
 * recognition ({@code audio/mixed.wav}, all voices on one track) and a copy to watch or listen to. A video becomes
 * {@code recording.mp4} (H.264 is copied, anything else is re-encoded by NVENC so the browser can play it); an
 * audio file is kept as it is.
 */
final class RecordingImport {

    /** Track kind of the uploaded file until it has been prepared. */
    static final String UPLOAD = "upload";
    /** Track kind and transcript source of a recording where everyone speaks on one track. */
    static final String MIXED = "mixed";
    /** Track kind of an uploaded audio file kept for listening. */
    static final String AUDIO = "audio";

    static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "m4a", "wav", "ogg", "oga", "opus", "flac", "aac", "wma");
    static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mkv", "webm", "mov", "avi", "m4v", "wmv");
    /** Images some audio files carry as a "video" stream (album covers); they are not a video to keep. */
    private static final Set<String> COVER_CODECS = Set.of("mjpeg", "png", "bmp", "gif");
    /** Frame rate limit of re-encoded video. */
    static final int MAX_FPS = 30;

    private RecordingImport() {}

    static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isSupported(String fileName) {
        String extension = extension(fileName);
        return AUDIO_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension);
    }

    /** The streams of a media file: the codec of the first real video stream (null if none) and its number of audio tracks. */
    record Streams(String videoCodec, int audioTracks) {
        boolean hasAudio() {
            return audioTracks > 0;
        }
    }

    static Streams streams(Ffmpeg ffmpeg, Path file) throws IOException {
        Ffmpeg.Result result = ffmpeg.probe(List.of("-v", "error", "-show_entries", "stream=codec_type,codec_name",
                "-of", "csv=p=0", file.toString()), Duration.ofMinutes(1));
        if (result.exitCode() != 0) throw new IOException("The file cannot be read as audio or video: " + result.output().strip());
        return parseStreams(result.output());
    }

    /** ffprobe lines like {@code h264,video} and {@code aac,audio}. */
    static Streams parseStreams(String probeOutput) {
        String video = null;
        int audio = 0;
        for (String line : probeOutput.lines().map(String::strip).toList()) {
            String[] parts = line.split(",");
            if (parts.length < 2) continue;
            if (parts[1].equals("audio")) audio++;
            if (parts[1].equals("video") && video == null && !COVER_CODECS.contains(parts[0])) video = parts[0];
        }
        return new Streams(video, audio);
    }

    /**
     * Prepares the uploaded file and reports how far it got; returns the meeting's new tracks. The upload is removed
     * once it has been converted.
     */
    static List<Meeting.Track> prepare(Ffmpeg ffmpeg, Path folder, Meeting.Track upload, ProgressListener progress) throws IOException {
        Path source = folder.resolve(upload.file());
        Streams streams = streams(ffmpeg, source);
        if (!streams.hasAudio()) throw new IOException("The uploaded file has no sound, so there is nothing to transcribe");
        double duration = ffmpeg.durationSeconds(source);
        // Extracting the sound reads only the audio and takes seconds; converting a video takes the rest of the time
        boolean hasVideo = streams.videoCodec() != null;

        Path speech = folder.resolve("audio/mixed.wav");
        Files.createDirectories(speech.getParent());
        run(ffmpeg, speechArgs(source, streams.audioTracks(), speech), speech, duration, hasVideo ? progress.range(0, 0.1) : progress);

        List<Meeting.Track> tracks = new ArrayList<>();
        if (hasVideo) {
            Path video = folder.resolve(MeetingRecorder.RECORDING_FILE);
            run(ffmpeg, videoArgs(source, streams.videoCodec(), streams.audioTracks(), video), video, duration, progress.range(0.1, 1));
            Files.delete(source);
            tracks.add(new Meeting.Track(MeetingRecorder.SCREEN, MeetingRecorder.RECORDING_FILE, 0, ffmpeg.durationSeconds(video)));
        } else {
            // An audio file is kept as uploaded: browsers play the usual formats
            String name = "recording." + extension(source.getFileName().toString());
            Path kept = folder.resolve(name);
            Files.move(source, kept, StandardCopyOption.REPLACE_EXISTING);
            tracks.add(new Meeting.Track(AUDIO, name, 0, ffmpeg.durationSeconds(kept)));
        }
        deleteIfEmpty(source.getParent());
        tracks.add(new Meeting.Track(MIXED, "audio/mixed.wav", 0, ffmpeg.durationSeconds(speech)));
        return tracks;
    }

    /** The sound for speech recognition: all audio tracks as one mono 16 kHz WAV. */
    static List<String> speechArgs(Path source, int audioTracks, Path output) {
        List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", source.toString()));
        args.addAll(audioMap(audioTracks));
        args.addAll(List.of("-ac", "1", "-ar", String.valueOf(AudioCapture.SAMPLE_RATE), "-c:a", "pcm_s16le", output.toString()));
        return args;
    }

    /**
     * H.264 video is copied; other codecs (VP9, HEVC, MPEG-4...) are re-encoded on the GPU. NVENC is the slow part
     * (decoding on the GPU did not help: a 1080p60 HEVC screen recording decodes faster on the CPU), so it uses its
     * fastest preset: twice as fast at the same size and quality level. Faster video is capped at 30 fps, which is
     * enough to follow a meeting and halves the frames to encode; slower video keeps its own rate. The sound becomes
     * one AAC track.
     */
    static List<String> videoArgs(Path source, String videoCodec, int audioTracks, Path output) {
        List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", source.toString(), "-map", "0:v:0"));
        args.addAll(audioMap(audioTracks));
        if (videoCodec.equals("h264")) {
            args.addAll(List.of("-c:v", "copy"));
        } else {
            args.addAll(List.of("-fpsmax", String.valueOf(MAX_FPS), "-c:v", "h264_nvenc", "-preset", "p1", "-pix_fmt", "yuv420p",
                    "-rc", "vbr", "-cq", "26", "-b:v", "0", "-maxrate", "10M", "-bufsize", "20M"));
        }
        args.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", output.toString()));
        return args;
    }

    /**
     * The only audio track, or all of them mixed into one: OBS and some call recorders put each source (microphone,
     * desktop sound) on its own track. amix scales the tracks down so that tracks repeating the same voices (OBS
     * track 1 is often the full mix) do not clip.
     */
    static List<String> audioMap(int audioTracks) {
        if (audioTracks <= 1) return List.of("-map", "0:a:0");
        StringBuilder mix = new StringBuilder();
        for (int i = 0; i < audioTracks; i++) mix.append("[0:a:").append(i).append(']');
        mix.append("amix=inputs=").append(audioTracks).append(":duration=longest[mixed]");
        return List.of("-filter_complex", mix.toString(), "-map", "[mixed]");
    }

    private static void run(Ffmpeg ffmpeg, List<String> args, Path output, double durationSeconds, ProgressListener progress) throws IOException {
        Ffmpeg.Result result = ffmpeg.run(args, Duration.ofHours(2), durationSeconds, progress);
        if (result.exitCode() != 0 || !Files.isRegularFile(output)) {
            Files.deleteIfExists(output);
            throw new IOException("Cannot prepare " + output.getFileName() + ": " + result.output().strip());
        }
        // ffmpeg's last progress time is the start of the last frame, a little short of the full duration
        progress.onProgress(1);
    }

    private static void deleteIfEmpty(Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // not empty: keep it
        }
    }
}
