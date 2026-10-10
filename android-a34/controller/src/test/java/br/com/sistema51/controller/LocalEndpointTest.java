package br.com.sistema51.controller;
import org.junit.Test;
import static org.junit.Assert.*;
public final class LocalEndpointTest {
    @Test public void privateAddressesAndPortAreNormalized(){assertEquals("http://192.168.1.101:8051",LocalEndpoint.parse("192.168.1.101"));assertEquals("http://10.0.0.2:8051",LocalEndpoint.parse("http://10.0.0.2:8051/"));}
    @Test public void publicFileAndAmbiguousUrlsAreRejected(){for(String value:new String[]{"https://192.168.1.1","8.8.8.8","file:///x","192.168.1.1.evil.example","192.168.001.1","http://user@192.168.1.1:8051","http://192.168.1.1:8051?next=x","http://172.32.1.1"}){try{LocalEndpoint.parse(value);fail(value);}catch(IllegalArgumentException expected){}}}
    @Test public void webviewCannotNavigateToDifferentHostPortOrScheme(){String base="http://192.168.1.1:8051";assertTrue(LocalEndpoint.sameOrigin(base,base+"/app.js"));assertFalse(LocalEndpoint.sameOrigin(base,"http://192.168.1.2:8051"));assertFalse(LocalEndpoint.sameOrigin(base,"http://192.168.1.1:80"));assertFalse(LocalEndpoint.sameOrigin(base,"file:///x"));}
}
