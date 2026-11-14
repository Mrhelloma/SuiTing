package cn.musicsquare.app;

/** A short genuine song must not be confused with a preview of a much longer song. */
public final class PreviewPolicy {
    public static boolean isShortPreview(long fullDurationMs,long deliveredDurationMs){
        return fullDurationMs>90000&&deliveredDurationMs>0&&deliveredDurationMs<=90000
            &&fullDurationMs-deliveredDurationMs>15000&&deliveredDurationMs<fullDurationMs*.7;
    }
}
