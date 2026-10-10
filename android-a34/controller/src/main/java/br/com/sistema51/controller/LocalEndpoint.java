package br.com.sistema51.controller;

import java.net.URI;

/** Controller navigation is limited to literal private IPv4 LAN endpoints. */
public final class LocalEndpoint {
    private LocalEndpoint() {}
    public static String parse(String supplied){
        try{
            String value=supplied.trim();if(!value.contains("://"))value="http://"+value;
            URI uri=new URI(value);String host=uri.getHost();
            if(!"http".equals(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
                throw new IllegalArgumentException();
            if(uri.getPath()!=null&&!uri.getPath().isEmpty()&&!"/".equals(uri.getPath()))throw new IllegalArgumentException();
            String[] parts=host.split("\\.",-1);if(parts.length!=4)throw new IllegalArgumentException();
            int[] ip=new int[4];for(int i=0;i<4;i++){
                if(!parts[i].matches("0|[1-9][0-9]{0,2}"))throw new IllegalArgumentException();
                ip[i]=Integer.parseInt(parts[i]);if(ip[i]>255)throw new IllegalArgumentException();
            }
            if(!(ip[0]==10||ip[0]==192&&ip[1]==168||ip[0]==172&&ip[1]>=16&&ip[1]<=31))throw new IllegalArgumentException();
            int port=uri.getPort()<0?8051:uri.getPort();if(port<1||port>65535)throw new IllegalArgumentException();
            return "http://"+host+":"+port;
        }catch(Exception error){throw new IllegalArgumentException("Informe o IP local do A34, por exemplo 192.168.1.101:8051.");}
    }
    public static boolean sameOrigin(String base,String candidate){
        try{URI a=new URI(base),b=new URI(candidate);return a.getScheme().equals(b.getScheme())&&a.getHost().equals(b.getHost())&&a.getPort()==b.getPort()&&b.getUserInfo()==null;}
        catch(Exception ignored){return false;}
    }
}
