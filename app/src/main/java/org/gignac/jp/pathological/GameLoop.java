package org.gignac.jp.pathological;
import android.os.*;

public class GameLoop
    implements Runnable
{
    final Handler handler = new Handler();
    private Runnable update;
    private Runnable render;
    private long delayMillis;
    private long targetTime;
    private boolean rendering = false;

    public GameLoop(Runnable update, Runnable render, int delayMillis) {
        this.update = update;
        this.render = render;
        this.delayMillis = delayMillis;
    }

    public void start() {
        targetTime = SystemClock.uptimeMillis()+delayMillis;
        handler.removeCallbacks(this);
        handler.postAtTime(this, targetTime);
    }

    public void stop() {
        handler.removeCallbacks(this);
    }

    public void run() {
        long curTime = SystemClock.uptimeMillis();
        if(rendering) {
            if( curTime < targetTime) {
                handler.postAtTime(this,targetTime);
                rendering = false;
                return;
            }
            while( SystemClock.uptimeMillis() >= targetTime + 4*delayMillis)
                targetTime += delayMillis;
            while( SystemClock.uptimeMillis() >= targetTime) {
                update.run();
                targetTime += delayMillis;
            }
        }
        update.run();
        targetTime += delayMillis;
        render.run();
        rendering = true;
    }
}
