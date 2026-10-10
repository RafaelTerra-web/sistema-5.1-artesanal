package br.com.sistema51.a34;

import android.app.Application;
import br.com.sistema51.a34.ui.AppBridge;

public final class A34Application extends Application {
    private AppFacade facade;
    public synchronized AppFacade facade(){
        if(facade==null){facade=new AppFacade(this);facade.retainForApplication();}
        return facade;
    }
    @Override public void onCreate(){super.onCreate();AppBridge.installFactory(context->facade());}
}
