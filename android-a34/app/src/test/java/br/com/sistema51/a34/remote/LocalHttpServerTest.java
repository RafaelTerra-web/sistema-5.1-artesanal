package br.com.sistema51.a34.remote;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class LocalHttpServerTest {
    private LocalHttpServer.Request parse(String text)throws IOException{return LocalHttpServer.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));}
    private void rejects(String request){try{parse(request);fail("Request accepted");}catch(IOException expected){}}
    @Test public void parsesJsonWithExactUtf8Length()throws Exception{
        String body="{\"name\":\"áudio\"}";
        LocalHttpServer.Request r=parse("POST /api/profile HTTP/1.1\r\nHost: 127.0.0.1:8051\r\nContent-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n"+body);
        assertEquals(body,r.body);assertEquals("127.0.0.1:8051",r.headers.get("host"));
    }
    @Test public void rejectsDuplicateLength(){rejects("POST /api/profile HTTP/1.1\r\nContent-Length: 0\r\ncontent-length: 2\r\n\r\n{}");}
    @Test public void rejectsChunking(){rejects("POST /api/profile HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n");}
    @Test public void rejectsOversizedBody(){rejects("POST /api/profile HTTP/1.1\r\nContent-Length: 32769\r\n\r\n");}
    @Test public void rejectsTruncatedBody(){rejects("POST /api/profile HTTP/1.1\r\nContent-Length: 8\r\n\r\n{}");}
    @Test public void rejectsBadMethodAndPath(){rejects("DELETE /api/profile HTTP/1.1\r\n\r\n");rejects("GET http://example.org/ HTTP/1.1\r\n\r\n");}
    @Test public void refusesPublicPeerAddresses()throws Exception{assertFalse(LocalHttpServer.localAddress(InetAddress.getByName("8.8.8.8")));assertTrue(LocalHttpServer.localAddress(InetAddress.getByName("192.168.1.2")));}
    private String get(int port,String host,String origin)throws Exception{
        try(Socket s=new Socket("127.0.0.1",port)){
            s.setSoTimeout(4000);s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: "+host+"\r\n"+(origin==null?"":"Origin: "+origin+"\r\n")+"\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=s.getInputStream().read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");
        }
    }
    @Test public void rejectsDnsRebindingHostAndForeignOrigin()throws Exception{
        try(LocalHttpServer server=new LocalHttpServer(0,r->new LocalHttpServer.Response(200,"text/plain","ok"))){
            String host="127.0.0.1:"+server.port();
            assertTrue(get(server.port(),"attacker.example",null).startsWith("HTTP/1.1 403"));
            assertTrue(get(server.port(),host,"http://attacker.example").startsWith("HTTP/1.1 403"));
            assertTrue(get(server.port(),host,"http://"+host).endsWith("ok"));
        }
    }
    @Test public void closingServerReleasesPort()throws Exception{
        LocalHttpServer server=new LocalHttpServer(0,r->new LocalHttpServer.Response(200,"text/plain","ok"));int port=server.port();server.close();
        try(ServerSocket second=new ServerSocket(port)){assertEquals(port,second.getLocalPort());}
    }
}
