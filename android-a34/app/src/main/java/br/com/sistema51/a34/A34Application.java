package br.com.sistema51.a34;

import android.app.Application;
import br.com.sistema51.a34.ui.AppBridge;

public final class A34Application extends Application {
    @Override public void onCreate(){super.onCreate();AppBridge.installFactory(AppFacade::new);}
}
