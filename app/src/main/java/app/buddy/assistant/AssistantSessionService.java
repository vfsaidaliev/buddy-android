package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Makes the assistant's session: voice input over whatever is on the screen, which it gets to see (AssistContext). */
public class AssistantSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new Session(this);
    }

    static final class Session extends VoiceInteractionSession {
        private final Handler main = new Handler(Looper.getMainLooper());

        Session(Context c) {
            super(c);
        }

        @Override
        public void onShow(Bundle args, int flags) {
            super.onShow(args, flags);
            AssistContext.start();
            startAssistantActivity(new Intent(getContext(), VoiceActivity.class).putExtra("listen", true)
                    .putExtra("assist", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // the screen's text and screenshot come right after; then the empty session window goes
            main.postDelayed(this::hide, 3000);
        }

        @Override
        public void onHandleAssist(AssistState state) {
            if (state != null) AssistContext.setStructure(state.getAssistStructure());
        }

        @Override
        public void onHandleScreenshot(Bitmap screenshot) {
            AssistContext.setScreenshot(getContext(), screenshot);
        }
    }
}
