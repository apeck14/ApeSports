package tv.gridiron.app;

public final class ApeSportsApplication extends android.app.Application {
    @Override public void onCreate(){
        super.onCreate();Diagnostics.init(this);Diagnostics.log("process_start","version="+BuildConfig.VERSION_NAME);
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{Diagnostics.crash(error);}catch(Throwable ignored){}
            finally{if(previous!=null)previous.uncaughtException(thread,error);else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}}
        });
    }
}
