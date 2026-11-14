package cn.musicsquare.app;
import org.json.*;
import java.net.*;
import java.io.*;

/** Optional network check: exercises the same adapters as the APK; never logs signed URLs. */
public class LiveAdapterProbe {
    public static void main(String[] args)throws Exception{
        JSONArray netease=MusicApi.search("netease","林俊杰",5);
        if(netease.length()==0)throw new AssertionError("NetEase returned no songs");
        System.out.println("PASS NetEase public search: "+netease.length()+" songs; first id="+netease.getJSONObject(0).getString("songid"));
        JSONArray qq=MusicApi.search("qq","Always Online",10);JSONObject target=null;
        for(int i=0;i<qq.length();i++)if(qq.getJSONObject(i).optString("artist").equals("林俊杰")){target=qq.getJSONObject(i);break;}
        if(target==null)throw new AssertionError("QQ test song not returned");
        JSONObject playable=MusicApi.resolve(target);String url=playable.getString("audioUrl");
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Range","bytes=0-65535");c.setRequestProperty("Referer","https://y.qq.com/");c.setRequestProperty("User-Agent","Mozilla/5.0 MusicSquare/0.1.1");
        int status=c.getResponseCode();if(status!=200&&status!=206)throw new AssertionError("audio HTTP "+status);
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[4096];int n;while(out.size()<65536&&(n=in.read(b,0,Math.min(b.length,65536-out.size())))!=-1)out.write(b,0,n);}
        byte[] sample=out.toByteArray();c.disconnect();if(sample.length<16)throw new AssertionError("audio sample too short");
        String signature=new String(sample,0,Math.min(sample.length,20),java.nio.charset.StandardCharsets.ISO_8859_1);
        if(!signature.contains("ftyp")&&!signature.startsWith("fLaC")&&!signature.startsWith("ID3"))throw new AssertionError("unrecognized audio sample");
        if(args.length>0)try(FileOutputStream file=new FileOutputStream(args[0])){file.write(sample);}
        System.out.println("PASS QQ resolve and ranged audio read: scheme="+new URI(url).getScheme()+", host="+new URI(url).getHost()+", HTTP "+status+", bytes="+sample.length);
        System.out.println("Scope: server-side adapter/resource validation only, not Android-device playback.");
    }
}
