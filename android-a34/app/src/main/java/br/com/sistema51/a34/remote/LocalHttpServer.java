package br.com.sistema51.a34.remote;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Bounded HTTP/1.1 endpoint. One request per connection, no chunked bodies or CORS. */
public final class LocalHttpServer implements AutoCloseable {
    public interface Handler { Response handle(Request request) throws Exception; }
    public static final class Request {
        public final String method, path;
        public final Map<String,String> headers;
        public final String body;
        Request(String method,String path,Map<String,String> headers,String body) {
            this.method=method;this.path=path;this.headers=headers;this.body=body;
        }
    }
    public static final class Response {
        public final int status; public final String type; public final byte[] body;
        public Response(int status,String type,String body) { this.status=status;this.type=type;this.body=body.getBytes(StandardCharsets.UTF_8); }
    }
    private final ServerSocket server;
    private final Handler handler;
    private final ThreadPoolExecutor clients=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    private final Set<Socket> sockets=Collections.synchronizedSet(new HashSet<>());
    private volatile boolean closed;
    public LocalHttpServer(int port,Handler handler)throws IOException {
        this.handler=handler;server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(port),8);
        Thread accept=new Thread(this::accept,"A34-LAN-listener");accept.setDaemon(true);accept.start();
    }
    public int port(){return server.getLocalPort();}
    private void accept() {
        while(!closed)try {
            Socket socket=server.accept();
            if(!localAddress(socket.getInetAddress())){socket.close();continue;}
            sockets.add(socket);
            try{clients.execute(()->serve(socket));}catch(RejectedExecutionException e){sockets.remove(socket);socket.close();}
        }catch(IOException e){if(!closed)close();}
    }
    public static boolean localAddress(InetAddress address){return address.isLoopbackAddress()||address.isSiteLocalAddress();}
    private void serve(Socket socket) {
        try(Socket connection=socket){
            connection.setSoTimeout(3000);
            Response response;
            try{
                Request request=parse(connection.getInputStream());
                String host=request.headers.get("host");
                String expected=connection.getLocalAddress().getHostAddress()+":"+port();
                if(host==null||!host.equals(expected))response=new Response(403,"application/json","{\"error\":\"Use o endereço IP local do A34.\"}");
                else if(request.headers.containsKey("origin")&&!request.headers.get("origin").equals("http://"+host))
                    response=new Response(403,"application/json","{\"error\":\"Origem recusada.\"}");
                else response=handler.handle(request);
            }catch(IOException|IllegalArgumentException e){response=new Response(400,"application/json","{\"error\":\"Requisição inválida.\"}");}
            catch(Exception e){response=new Response(500,"application/json","{\"error\":\"Operação não concluída.\"}");}
            OutputStream out=connection.getOutputStream();
            String headers="HTTP/1.1 "+response.status+" Response\r\nContent-Type: "+response.type+"; charset=utf-8\r\nContent-Length: "+response.body.length
                +"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
                +"\r\nContent-Security-Policy: default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action 'self'\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(response.body);out.flush();
        }catch(IOException ignored){}finally{sockets.remove(socket);}
    }
    static Request parse(InputStream in)throws IOException {
        String line=line(in);String[] start=line.split(" ");
        if(start.length!=3||!"HTTP/1.1".equals(start[2])||!("GET".equals(start[0])||"POST".equals(start[0]))||!start[1].matches("/[A-Za-z0-9/._-]*"))throw new IOException("Request line");
        Map<String,String> headers=new HashMap<>();int total=line.length();
        while(!(line=line(in)).isEmpty()){
            total+=line.length();if(total>8192||headers.size()>=40)throw new IOException("Headers too large");
            int colon=line.indexOf(':');if(colon<1)throw new IOException("Header");
            String key=line.substring(0,colon).toLowerCase(Locale.ROOT);
            if(headers.put(key,line.substring(colon+1).trim())!=null)throw new IOException("Duplicate header");
        }
        if(headers.containsKey("transfer-encoding"))throw new IOException("Chunking unsupported");
        int size;try{size=Integer.parseInt(headers.getOrDefault("content-length","0"));}catch(NumberFormatException e){throw new IOException("Length");}
        if(size<0||size>32768)throw new IOException("Body too large");
        byte[] body=new byte[size];int offset=0;while(offset<size){int n=in.read(body,offset,size-offset);if(n<0)throw new EOFException();offset+=n;}
        return new Request(start[0],start[1],headers,new String(body,StandardCharsets.UTF_8));
    }
    private static String line(InputStream in)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int prior=-1;
        while(out.size()<4096){int value=in.read();if(value<0)throw new EOFException();if(prior==13&&value==10){byte[] b=out.toByteArray();return new String(b,0,b.length-1,StandardCharsets.US_ASCII);}out.write(value);prior=value;}
        throw new IOException("Line too long");
    }
    @Override public void close(){closed=true;try{server.close();}catch(IOException ignored){}synchronized(sockets){for(Socket s:sockets)try{s.close();}catch(IOException ignored){}}clients.shutdownNow();}
}
