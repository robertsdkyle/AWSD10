/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.content.Context;

import java.io.File;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.utils.DeviceProtectedUtils;

/**
 * This class gathers audio feedback and haptic feedback functions.
 * <p>
 * It offers a consistent and simple interface that allows LatinIME to forget about the
 * complexity of settings and the like.
 */
public final class AudioAndHapticFeedbackManager {
    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private SettingsValues mSettingsValues;
    private boolean mSoundOn;
    private boolean mDoNotDisturb;

    private static final AudioAndHapticFeedbackManager sInstance =
            new AudioAndHapticFeedbackManager();

    public static AudioAndHapticFeedbackManager getInstance() {
        return sInstance;
    }

    private AudioAndHapticFeedbackManager() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final Context context) {
        sInstance.initInternal(context);
    }

    private void initInternal(final Context context) {
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mContext = context.getApplicationContext();
    }

    // custom key sound packs: [pack][key, space, delete, enter, extra key variants...]
    private static final String[] SOUND_PACKS = {
        "typewriter", "retro", "bubble", "thock", "piano", "rain", "laser", "wood", "arcade", "chimes", "drums"
    };
    private static final int[][] SOUND_RES = {
        { R.raw.snd_typewriter_key, R.raw.snd_typewriter_space, R.raw.snd_typewriter_delete, R.raw.snd_typewriter_enter },
        { R.raw.snd_retro_key, R.raw.snd_retro_space, R.raw.snd_retro_delete, R.raw.snd_retro_enter },
        { R.raw.snd_bubble_key, R.raw.snd_bubble_space, R.raw.snd_bubble_delete, R.raw.snd_bubble_enter },
        { R.raw.snd_thock_key, R.raw.snd_thock_space, R.raw.snd_thock_delete, R.raw.snd_thock_enter },
        { R.raw.snd_piano_key, R.raw.snd_piano_space, R.raw.snd_piano_delete, R.raw.snd_piano_enter,
                R.raw.snd_piano_key0, R.raw.snd_piano_key1, R.raw.snd_piano_key2, R.raw.snd_piano_key3, R.raw.snd_piano_key4 },
        { R.raw.snd_rain_key, R.raw.snd_rain_space, R.raw.snd_rain_delete, R.raw.snd_rain_enter },
        { R.raw.snd_laser_key, R.raw.snd_laser_space, R.raw.snd_laser_delete, R.raw.snd_laser_enter },
        { R.raw.snd_wood_key, R.raw.snd_wood_space, R.raw.snd_wood_delete, R.raw.snd_wood_enter },
        { R.raw.snd_arcade_key, R.raw.snd_arcade_space, R.raw.snd_arcade_delete, R.raw.snd_arcade_enter },
        { R.raw.snd_chimes_key, R.raw.snd_chimes_space, R.raw.snd_chimes_delete, R.raw.snd_chimes_enter },
        { R.raw.snd_drums_key, R.raw.snd_drums_space, R.raw.snd_drums_delete, R.raw.snd_drums_enter },
    };
    /** File names for the "custom" pack, stored in [getCustomSoundDir]. */
    public static final String[] CUSTOM_SOUND_NAMES = { "key", "space", "delete", "enter" };
    private Context mContext;
    private SoundPool mSoundPool;
    private String mLoadedPack;
    private int[] mSoundIds;

    public static File getCustomSoundDir(final Context context) {
        return new File(DeviceProtectedUtils.getFilesDir(context), "custom_sounds");
    }

    /** Call after custom sound files change, so they get loaded again. */
    public void reloadSounds() {
        if (mSoundPool != null) mSoundPool.release();
        mSoundPool = null;
        mLoadedPack = null;
    }

    private void loadPack(final String pack) {
        if (mSoundPool == null) {
            mSoundPool = new SoundPool.Builder()
                    .setMaxStreams(6)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
        } else if (mSoundIds != null) {
            for (int id : mSoundIds) if (id != 0) mSoundPool.unload(id);
        }
        mLoadedPack = pack;
        if ("custom".equals(pack)) {
            final File dir = getCustomSoundDir(mContext);
            mSoundIds = new int[CUSTOM_SOUND_NAMES.length];
            for (int i = 0; i < CUSTOM_SOUND_NAMES.length; i++) {
                final File f = new File(dir, CUSTOM_SOUND_NAMES[i]);
                mSoundIds[i] = f.exists() ? mSoundPool.load(f.getAbsolutePath(), 1) : 0;
            }
            return;
        }
        int packIndex = -1;
        for (int i = 0; i < SOUND_PACKS.length; i++)
            if (SOUND_PACKS[i].equals(pack)) packIndex = i;
        if (packIndex < 0) {
            mSoundIds = null;
            return;
        }
        mSoundIds = new int[SOUND_RES[packIndex].length];
        for (int i = 0; i < mSoundIds.length; i++)
            mSoundIds[i] = mSoundPool.load(mContext, SOUND_RES[packIndex][i], 1);
    }

    /** Plays a sound from the chosen pack, returns false if the system click should be used instead. */
    private boolean playCustomSound(final int code) {
        final String pack = mSettingsValues.mKeySoundPack;
        if (pack == null || "system".equals(pack) || mContext == null) return false;
        if (!pack.equals(mLoadedPack)) loadPack(pack);
        if (mSoundIds == null) return false;
        int which = switch (code) {
            case Constants.CODE_SPACE -> 1;
            case KeyCode.DELETE -> 2;
            case Constants.CODE_ENTER -> 3;
            default -> 0;
        };
        // packs with extra key variants (piano) pick one based on the key, so the same key plays the same note
        if (which == 0 && mSoundIds.length > 4)
            which = 4 + Math.floorMod(code, mSoundIds.length - 4);
        // a custom sound may be missing for some keys: fall back to the "key" sound, then the system click
        int id = mSoundIds[which];
        if (id == 0) id = mSoundIds[0];
        if (id == 0) return false;
        final float volume = mSettingsValues.mKeypressSoundVolume < 0 ? 0.6f : mSettingsValues.mKeypressSoundVolume;
        mSoundPool.play(id, volume, volume, 1, 0, 1f);
        return true;
    }

    public void performHapticAndAudioFeedback(
        final int code,
        final View viewToPerformHapticFeedbackOn,
        final HapticEvent hapticEvent
    ) {
        performHapticFeedback(viewToPerformHapticFeedbackOn, hapticEvent);
        performAudioFeedback(code, hapticEvent);
    }

    public boolean hasVibrator() {
        return mVibrator != null && mVibrator.hasVibrator();
    }

    public void vibrate(final long milliseconds) {
        if (mVibrator == null || milliseconds <= 0) {
            return;
        }
        mVibrator.vibrate(milliseconds);
    }

    private boolean reevaluateIfSoundIsOn() {
        if (mSettingsValues == null || !mSettingsValues.mSoundOn || mAudioManager == null || mDoNotDisturb) {
            return false;
        }
        return mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    public void performAudioFeedback(final int code, final HapticEvent hapticEvent) {
        // if mAudioManager is null, we can't play a sound anyway, so return
        if (mAudioManager == null) {
            return;
        }
        if (!mSoundOn) {
            return;
        }
        if (hapticEvent != HapticEvent.KEY_PRESS) {
            return;
        }
        if (playCustomSound(code)) {
            return;
        }
        final int sound = switch (code) {
            case KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE;
            case Constants.CODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN;
            case Constants.CODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR;
            default -> AudioManager.FX_KEYPRESS_STANDARD;
        };
        mAudioManager.playSoundEffect(sound, mSettingsValues.mKeypressSoundVolume);
    }

    public void performHapticFeedback(final View viewToPerformHapticFeedbackOn, final HapticEvent hapticEvent) {
        if (!mSettingsValues.mVibrateOn || (mDoNotDisturb && !mSettingsValues.mVibrateInDndMode)) {
            return;
        }
        if (hapticEvent == HapticEvent.NO_HAPTICS) {
            // Avoid surprises with the handling of HapticFeedbackConstants.NO_HAPTICS
            return;
        }
        if (hapticEvent.allowCustomDuration && mSettingsValues.mKeypressVibrationDuration >= 0) {
            vibrate(mSettingsValues.mKeypressVibrationDuration);
            return;
        }
        // Go ahead with the system default
        if (viewToPerformHapticFeedbackOn != null) {
            viewToPerformHapticFeedbackOn.performHapticFeedback(
                    hapticEvent.feedbackConstant,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    public void onSettingsChanged(final SettingsValues settingsValues) {
        mSettingsValues = settingsValues;
        mSoundOn = reevaluateIfSoundIsOn();
    }

    public void onRingerModeChanged(boolean doNotDisturb) {
        mDoNotDisturb = doNotDisturb;
        mSoundOn = reevaluateIfSoundIsOn();
    }
}
