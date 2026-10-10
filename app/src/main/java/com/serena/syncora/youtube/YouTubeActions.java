package com.serena.syncora.youtube;

import android.content.Context;

import com.serena.syncora.lyrics.Lyrics;
import com.serena.syncora.lyrics.LrcParser;
import com.serena.syncora.lyrics.LyricsClient;
import com.serena.syncora.lyrics.SubtitleParser;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** The things the YouTube page can save: video, audio, thumbnail, subtitles and lyrics. */
public final class YouTubeActions {

    private YouTubeActions() {
    }

    /** Video with sound as an MP4 file (muxes a video-only stream with AAC audio when needed). */
    public static DownloadQueue.Work video(final Context context, final YouTubeVideo video, final YouTubeFormat format) {
        return new DownloadQueue.Work() {
            @Override
            public void run(DownloadQueue.Job job) throws Exception {
                File cache = context.getCacheDir();
                String name = DownloadStore.safeName(video.title, video.id, "mp4");
                if (format.kind == YouTubeFormat.Kind.MUXED) {
                    File file = new File(cache, "yt-" + video.id + "-" + format.itag + ".part");
                    try {
                        YouTubeDownloader.download(format.url, video.userAgent, format.contentLength, file,
                                new YouTubeDownloader.Progress() {
                                    @Override
                                    public void onProgress(float fraction) {
                                        progressOf(job, fraction);
                                    }
                                }, job.cancelled);
                    } catch (IOException e) {
                        file.delete();
                        throw e;
                    }
                    job.location = DownloadStore.save(context, file, name, "video/mp4").location;
                    return;
                }
                YouTubeFormat audio = video.bestMuxAudio();
                if (audio == null) throw new IOException("No AAC audio stream is available for this video");
                File videoFile = new File(cache, "yt-" + video.id + "-v" + format.itag + ".part");
                File audioFile = new File(cache, "yt-" + video.id + "-a" + audio.itag + ".part");
                File output = new File(cache, "yt-" + video.id + "-out.mp4");
                try {
                    YouTubeDownloader.download(format.url, video.userAgent, format.contentLength, videoFile,
                            new YouTubeDownloader.Progress() {
                                @Override
                                public void onProgress(float fraction) {
                                    progressOf(job, fraction * 0.8f);
                                }
                            }, job.cancelled);
                    YouTubeDownloader.download(audio.url, video.userAgent, audio.contentLength, audioFile,
                            new YouTubeDownloader.Progress() {
                                @Override
                                public void onProgress(float fraction) {
                                    progressOf(job, 0.8f + fraction * 0.1f);
                                }
                            }, job.cancelled);
                    YouTubeDownloader.mux(videoFile, audioFile, output);
                    progressOf(job, 0.95f);
                    job.location = DownloadStore.save(context, output, name, "video/mp4").location;
                } finally {
                    videoFile.delete();
                    audioFile.delete();
                    output.delete();
                }
            }
        };
    }

    /** The audio stream as it is: AAC in an .m4a file or Opus in a .webm file. */
    public static DownloadQueue.Work audio(final Context context, final YouTubeVideo video, final YouTubeFormat format) {
        return new DownloadQueue.Work() {
            @Override
            public void run(DownloadQueue.Job job) throws Exception {
                File file = new File(context.getCacheDir(), "yt-" + video.id + "-audio-" + format.itag + ".part");
                try {
                    YouTubeDownloader.download(format.url, video.userAgent, format.contentLength, file,
                            new YouTubeDownloader.Progress() {
                                @Override
                                public void onProgress(float fraction) {
                                    progressOf(job, fraction);
                                }
                            }, job.cancelled);
                } catch (IOException e) {
                    file.delete();
                    throw e;
                }
                String ext = format.extension();
                String mime = "m4a".equals(ext) ? "audio/mp4" : "audio/webm";
                job.location = DownloadStore.save(context, file, DownloadStore.safeName(video.title, video.id, ext), mime).location;
            }
        };
    }

    /** The highest quality thumbnail YouTube gave us, as a JPEG file. */
    public static DownloadQueue.Work thumbnail(final Context context, final YouTubeVideo video) {
        return new DownloadQueue.Work() {
            @Override
            public void run(DownloadQueue.Job job) throws Exception {
                File file = new File(context.getCacheDir(), "yt-" + video.id + "-thumb.part");
                try {
                    YouTubeDownloader.download(video.thumbnailUrl, video.userAgent, 0, file, null, job.cancelled);
                    if (file.length() == 0) throw new IOException("The thumbnail is empty");
                } catch (IOException e) {
                    file.delete();
                    throw e;
                }
                job.location = DownloadStore.save(context, file, DownloadStore.safeName(video.title, video.id, "jpg"),
                        "image/jpeg").location;
            }
        };
    }

    /** A caption track as a subtitle file (.srt or .vtt). */
    public static DownloadQueue.Work captions(final Context context, final YouTubeVideo video,
                                              final YouTubeVideo.Caption caption, final boolean srt) {
        return new DownloadQueue.Work() {
            @Override
            public void run(DownloadQueue.Job job) throws Exception {
                List<SubtitleParser.Cue> cues = YouTubeClient.fetchCues(caption, video.userAgent);
                if (cues.isEmpty()) throw new IOException("YouTube has no text for this subtitle track");
                progressOf(job, 0.8f);
                String text = srt ? SubtitleParser.toSrt(cues) : SubtitleParser.toVtt(cues);
                String lang = caption.languageCode.isEmpty() ? "" : "." + caption.languageCode;
                String name = DownloadStore.safeName(video.title, video.id + lang, srt ? "srt" : "vtt");
                job.location = DownloadStore.saveText(context, text, name, srt ? "application/x-subrip" : "text/vtt").location;
            }
        };
    }

    /**
     * Lyrics as an LRC file: the synchronised lyrics of the song when they exist, otherwise the video's captions
     * with their timing.
     */
    public static DownloadQueue.Work lyrics(final Context context, final YouTubeVideo video) {
        return new DownloadQueue.Work() {
            @Override
            public void run(DownloadQueue.Job job) throws Exception {
                Lyrics lyrics = YouTubeLyrics.find(video);
                if (lyrics == null || lyrics.isEmpty()) throw new IOException("No lyrics or captions were found for this video");
                progressOf(job, 0.8f);
                String ext = lyrics.synced() ? "lrc" : "txt";
                String text = lyrics.synced() ? LrcParser.toLrc(lyrics) : joinPlain(lyrics);
                job.location = DownloadStore.saveText(context, text,
                        DownloadStore.safeName(video.title, video.id, ext), "text/plain").location;
            }
        };
    }

    private static String joinPlain(Lyrics lyrics) {
        StringBuilder out = new StringBuilder();
        for (com.serena.syncora.lyrics.LyricLine l : lyrics.lines) out.append(l.text).append('\n');
        return out.toString();
    }

    private static void progressOf(DownloadQueue.Job job, float fraction) {
        job.setProgress(fraction);
    }
}
