package cn.musicsquare.app;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** HTTP is permitted only for known audio CDN domains, never for API calls. */
public final class MediaUrlPolicy {
    private static final String[] HTTP_DOMAINS={"qqmusic.qq.com","aqqmusic.tc.qq.com","music.126.net","kuwo.cn"};
    static boolean domainMatches(String host,String domain){return host.equals(domain)||host.endsWith("."+domain);}
    public static String normalize(String input)throws IOException {
        String url=input==null?"":input.trim();
        if(url.startsWith("//"))url="https:"+url;
        try {
            URI uri=new URI(url);String scheme=uri.getScheme();String host=uri.getHost();
            if(scheme==null||host==null||uri.getUserInfo()!=null)throw new IOException("音频链接格式无效");
            scheme=scheme.toLowerCase(Locale.ROOT);host=host.toLowerCase(Locale.ROOT);
            if(scheme.equals("https"))return url;
            if(scheme.equals("http"))for(String domain:HTTP_DOMAINS)if(domainMatches(host,domain))return url;
            throw new IOException("来源返回了暂不支持的音频地址（"+scheme+"://"+host+"）");
        }catch(java.net.URISyntaxException e){throw new IOException("音频链接格式无效",e);}
    }
}
