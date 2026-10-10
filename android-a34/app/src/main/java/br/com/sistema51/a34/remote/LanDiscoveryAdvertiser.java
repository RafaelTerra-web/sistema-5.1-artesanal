package br.com.sistema51.a34.remote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

/** Advertises the controller address, never PINs or bearer credentials. */
final class LanDiscoveryAdvertiser implements AutoCloseable {
    static final String TYPE="_sistema51._tcp.";
    private final NsdManager manager;
    private volatile boolean requested;
    private final NsdManager.RegistrationListener listener=new NsdManager.RegistrationListener(){
        @Override public void onServiceRegistered(NsdServiceInfo info) { }
        @Override public void onRegistrationFailed(NsdServiceInfo info,int error) { requested=false; }
        @Override public void onServiceUnregistered(NsdServiceInfo info) { }
        @Override public void onUnregistrationFailed(NsdServiceInfo info,int error) { }
    };
    LanDiscoveryAdvertiser(Context context,int port,String identity){
        manager=context.getSystemService(NsdManager.class);
        if(manager==null)return;
        NsdServiceInfo service=new NsdServiceInfo();
        service.setServiceName("A34 DSP");service.setServiceType(TYPE);service.setPort(port);
        service.setAttribute("protocol","1");service.setAttribute("id",identity);
        try{requested=true;manager.registerService(service,NsdManager.PROTOCOL_DNS_SD,listener);}
        catch(RuntimeException failure){requested=false;}
    }
    @Override public void close(){
        if(manager!=null&&requested){requested=false;try{manager.unregisterService(listener);}catch(IllegalArgumentException ignored){}}
    }
}
