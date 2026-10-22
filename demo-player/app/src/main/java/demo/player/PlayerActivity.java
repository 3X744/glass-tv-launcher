package demo.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;

/** Emulator-only stand-in for Spotify: a silent media session Glass's Now Playing pill can read and control. */
public class PlayerActivity extends Activity {
    private static MediaSession session;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (session == null) {
            session = new MediaSession(getApplicationContext(), "demo");
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { setState(PlaybackState.STATE_PLAYING); }
                @Override public void onPause() { setState(PlaybackState.STATE_PAUSED); }
            });
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Bleed (feat. Omar Apollo)")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Malcolm Todd")
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art())
                    .build());
            session.setActive(true);
            setState(PlaybackState.STATE_PLAYING);
        }
        moveTaskToBack(true);
    }

    private static void setState(int state) {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(state, 0, 1f)
                .build());
    }

    private static Bitmap art() {
        Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 256, 256, 0xFFFF3D7F, 0xFF5B2EFF, Shader.TileMode.CLAMP));
        new Canvas(bmp).drawRect(0, 0, 256, 256, p);
        return bmp;
    }
}
