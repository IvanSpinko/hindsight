package org.example.visiontrack.meeting;

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

    private RecordingImport() {}

    static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isSupported(String fileName) {
        String extension = extension(fileName);
        return AUDIO_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension);
    }

    /** The streams of a media file: the codec of the first real video stream (null if none) and whether it has sound. */
    record Streams(String videoCodec, boolean hasAudio) {}

    static Streams streams(Ffmpeg ffmpeg, Path file) throws IOException {
        Ffmpeg.Result result = ffmpeg.probe(List.of("-v", "error", "-show_entries", "stream=codec_type,codec_name",
                "-of", "csv=p=0", file.toString()), Duration.ofMinutes(1));
        if (result.exitCode() != 0) throw new IOException("The file cannot be read as audio or video: " + result.output().strip());
        return parseStreams(result.output());
    }

    /** ffprobe lines like {@code h264,video} and {@code aac,audio}. */
    static Streams parseStreams(String probeOutput) {
        String video = null;
        boolean audio = false;
        for (String line : probeOutput.lines().map(String::strip).toList()) {
            String[] parts = line.split(",");
            if (parts.length < 2) continue;
            if (parts[1].equals("audio")) audio = true;
            if (parts[1].equals("video") && video == null && !COVER_CODECS.contains(parts[0])) video = parts[0];
        }
        return new Streams(video, audio);
    }

    /** Prepares the uploaded file; returns the meeting's new tracks. The upload is removed once it has been converted. */
    static List<Meeting.Track> prepare(Ffmpeg ffmpeg, Path folder, Meeting.Track upload) throws IOException {
        Path source = folder.resolve(upload.file());
        Streams streams = streams(ffmpeg, source);
        if (!streams.hasAudio()) throw new IOException("The uploaded file has no sound, so there is nothing to transcribe");

        Path speech = folder.resolve("audio/mixed.wav");
        Files.createDirectories(speech.getParent());
        run(ffmpeg, List.of("-hide_banner", "-loglevel", "error", "-y", "-i", source.toString(), "-map", "0:a:0", "-vn",
                "-ac", "1", "-ar", String.valueOf(AudioCapture.SAMPLE_RATE), "-c:a", "pcm_s16le", speech.toString()), speech);

        List<Meeting.Track> tracks = new ArrayList<>();
        if (streams.videoCodec() != null) {
            Path video = folder.resolve(MeetingRecorder.RECORDING_FILE);
            run(ffmpeg, videoArgs(source, streams.videoCodec(), video), video);
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

    /** H.264 video is copied; other codecs (VP9, HEVC, MPEG-4...) are re-encoded on the GPU. The sound becomes AAC. */
    static List<String> videoArgs(Path source, String videoCodec, Path output) {
        List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", source.toString(),
                "-map", "0:v:0", "-map", "0:a:0"));
        if (videoCodec.equals("h264")) {
            args.addAll(List.of("-c:v", "copy"));
        } else {
            args.addAll(List.of("-c:v", "h264_nvenc", "-pix_fmt", "yuv420p", "-rc", "vbr", "-cq", "26", "-b:v", "0", "-maxrate", "10M", "-bufsize", "20M"));
        }
        args.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", output.toString()));
        return args;
    }

    private static void run(Ffmpeg ffmpeg, List<String> args, Path output) throws IOException {
        Ffmpeg.Result result = ffmpeg.run(args, Duration.ofHours(2));
        if (result.exitCode() != 0 || !Files.isRegularFile(output)) {
            Files.deleteIfExists(output);
            throw new IOException("Cannot prepare " + output.getFileName() + ": " + result.output().strip());
        }
    }

    private static void deleteIfEmpty(Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // not empty: keep it
        }
    }
}
