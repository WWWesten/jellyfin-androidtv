package tv.emby.embyatv.playback;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.View;

import java.io.File;
import java.util.List;

import mediabrowser.apiinteraction.ApiClient;
import mediabrowser.apiinteraction.EmptyResponse;
import mediabrowser.apiinteraction.Response;
import mediabrowser.apiinteraction.android.profiles.AndroidProfile;
import mediabrowser.model.dlna.PlaybackException;
import mediabrowser.model.dlna.StreamInfo;
import mediabrowser.model.dlna.SubtitleDeliveryMethod;
import mediabrowser.model.dlna.SubtitleProfile;
import mediabrowser.model.dlna.SubtitleStreamInfo;
import mediabrowser.model.dlna.VideoOptions;
import mediabrowser.model.dto.BaseItemDto;
import mediabrowser.model.dto.MediaSourceInfo;
import mediabrowser.model.entities.LocationType;
import mediabrowser.model.entities.MediaStream;
import mediabrowser.model.extensions.StringHelper;
import mediabrowser.model.library.PlayAccess;
import mediabrowser.model.livetv.ChannelInfoDto;
import mediabrowser.model.mediainfo.SubtitleTrackInfo;
import mediabrowser.model.session.PlayMethod;
import mediabrowser.model.session.PlaybackStartInfo;
import tv.emby.embyatv.R;
import tv.emby.embyatv.TvApp;
import tv.emby.embyatv.livetv.TvManager;
import tv.emby.embyatv.ui.ImageButton;
import tv.emby.embyatv.util.Utils;

/**
 * Created by Eric on 12/9/2014.
 */
public class PlaybackController {
    List<BaseItemDto> mItems;
    VideoManager mVideoManager;
    SubtitleHelper mSubHelper;
    int mCurrentIndex = 0;
    private long mCurrentPosition = 0;
    private PlaybackState mPlaybackState = PlaybackState.IDLE;
    private TvApp mApplication;

    private StreamInfo mCurrentStreamInfo;
    private List<SubtitleStreamInfo> mSubtitleStreams;

    private IPlaybackOverlayFragment mFragment;
    private View mSpinner;
    private Boolean spinnerOff = false;

    private VideoOptions mCurrentOptions;
    private int mDefaultSubIndex = -1;
    private int mDefaultAudioIndex = -1;

    private PlayMethod mPlaybackMethod = PlayMethod.Transcode;

    private Runnable mReportLoop;
    private Handler mHandler;
    private static int REPORT_INTERVAL = 3000;

    private long mNextItemThreshold = Long.MAX_VALUE;
    private boolean nextItemReported;
    private long mStartPosition = 0;
    private long mCurrentProgramEndTime;
    private long mCurrentProgramStartTime;
    private boolean isLiveTv;
    private String liveTvChannelName = "";
    private boolean useVlc = false;

    private boolean updateProgress = true;

    public PlaybackController(List<BaseItemDto> items, IPlaybackOverlayFragment fragment) {
        mItems = items;
        mFragment = fragment;
        mApplication = TvApp.getApplication();
        mHandler = new Handler();
        mSubHelper = new SubtitleHelper(TvApp.getApplication().getCurrentActivity());

    }

    public void init(VideoManager mgr, View spinner) {
        mVideoManager = mgr;
        mSpinner = spinner;
        setupCallbacks();
    }

    public PlayMethod getPlaybackMethod() {
        return mPlaybackMethod;
    }

    public void setPlaybackMethod(PlayMethod value) {
        mPlaybackMethod = value;
    }

    public BaseItemDto getCurrentlyPlayingItem() {
        return mItems.get(mCurrentIndex);
    }
    public MediaSourceInfo getCurrentMediaSource() { return mCurrentStreamInfo != null && mCurrentStreamInfo.getMediaSource() != null ? mCurrentStreamInfo.getMediaSource() : getCurrentlyPlayingItem().getMediaSources().get(0);}
    public StreamInfo getCurrentStreamInfo() { return mCurrentStreamInfo; }
    public boolean canSeek() {return !isLiveTv && mVideoManager != null && mVideoManager.canSeek();}
    public boolean isLiveTv() { return isLiveTv; }
    public int getSubtitleStreamIndex() {return (mCurrentOptions != null && mCurrentOptions.getSubtitleStreamIndex() != null) ? mCurrentOptions.getSubtitleStreamIndex() : -1; }
    public Integer getAudioStreamIndex() {
        return isTranscoding() ? mCurrentStreamInfo.getAudioStreamIndex() != null ? mCurrentStreamInfo.getAudioStreamIndex() : mCurrentOptions.getAudioStreamIndex() : Integer.valueOf(mVideoManager.getAudioTrack());
    }
    public List<SubtitleStreamInfo> getSubtitleStreams() { return mSubtitleStreams; }
    public SubtitleStreamInfo getSubtitleStreamInfo(int index) {
        for (SubtitleStreamInfo info : mSubtitleStreams) {
            if (info.getIndex() == index) return info;
        }

        return null;
    }

    public boolean isNativeMode() { return mVideoManager == null || mVideoManager.isNativeMode(); }

    public boolean isTranscoding() { return mCurrentStreamInfo != null && mCurrentStreamInfo.getPlayMethod() == PlayMethod.Transcode; }

    public boolean hasNextItem() { return mCurrentIndex < mItems.size() - 1; }
    public BaseItemDto getNextItem() { return hasNextItem() ? mItems.get(mCurrentIndex+1) : null; }

    public boolean isPlaying() {
        return mPlaybackState == PlaybackState.PLAYING;
    }

    public void setAudioDelay(long value) { if (mVideoManager != null) mVideoManager.setAudioDelay(value);}
    public long getAudioDelay() { return mVideoManager != null ? mVideoManager.getAudioDelay() : 0;}

    public void play(long position) {
        play(position, -1);
    }

    private void play(long position, int transcodedSubtitle) {
        if (!TvApp.getApplication().isValid()) {
            Utils.showToast(TvApp.getApplication(), "Playback not supported. Please unlock or become a supporter.");
            return;
        }

        if (TvApp.getApplication().isTrial()) {
            Utils.showToast(TvApp.getApplication(), TvApp.getApplication().getRegistrationString()+". Unlock or become a supporter for unlimited playback.");

        }

        mApplication.getLogger().Debug("Play called with pos: "+position);
        switch (mPlaybackState) {
            case PLAYING:
                // do nothing
                break;
            case PAUSED:
                // just resume
                mVideoManager.play();
                mPlaybackState = PlaybackState.PLAYING;
                if (mFragment != null) {
                    mFragment.setFadingEnabled(true);
                    mFragment.setPlayPauseActionState(ImageButton.STATE_SECONDARY);
                    mFragment.updateEndTime(mVideoManager.getDuration() - getCurrentPosition());
                }
                startReportLoop();
                break;
            case BUFFERING:
                // onPrepared should take care of it
                break;
            case IDLE:
                // start new playback
                BaseItemDto item = getCurrentlyPlayingItem();

                // make sure item isn't missing
                if (item.getLocationType() == LocationType.Virtual) {
                    if (hasNextItem()) {
                        new AlertDialog.Builder(mApplication.getCurrentActivity())
                                .setTitle("Episode Missing")
                                .setMessage("This episode is missing from your library.  Would you like to skip it and continue to the next one?")
                                .setPositiveButton(mApplication.getResources().getString(R.string.lbl_yes), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        next();
                                    }
                                })
                                .setNegativeButton(mApplication.getResources().getString(R.string.lbl_no), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        mApplication.getCurrentActivity().finish();
                                    }
                                })
                                .create()
                                .show();
                        return;
                    } else {
                        new AlertDialog.Builder(mApplication.getCurrentActivity())
                                .setTitle("Episode Missing")
                                .setMessage("This episode is missing from your library.  Playback will stop.")
                                .setPositiveButton(mApplication.getResources().getString(R.string.lbl_ok), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        mApplication.getCurrentActivity().finish();
                                    }
                                })
                                .create()
                                .show();
                        return;

                    }
                }

                // confirm we actually can play
                if (item.getPlayAccess() != PlayAccess.Full) {
                    String msg = item.getIsPlaceHolder() ? mApplication.getString(R.string.msg_cannot_play) : mApplication.getString(R.string.msg_cannot_play_time);
                    Utils.showToast(TvApp.getApplication(), msg);
                    return;
                }

                startSpinner();
                mCurrentOptions = new VideoOptions();
                mCurrentOptions.setDeviceId(mApplication.getApiClient().getDeviceId());
                mCurrentOptions.setItemId(item.getId());
                mCurrentOptions.setMediaSources(item.getMediaSources());
                mCurrentOptions.setMaxBitrate(getMaxBitrate());
                mCurrentOptions.setSubtitleStreamIndex(transcodedSubtitle >= 0 ? transcodedSubtitle : null);
                mCurrentOptions.setMediaSourceId(transcodedSubtitle >= 0 ? getCurrentMediaSource().getId() : null);
                TvApp.getApplication().getLogger().Debug("Max bitrate is: " + getMaxBitrate());
                isLiveTv = item.getType().equals("TvChannel");

                // Create our profile - use VLC unless live tv or on FTV stick and over SD
                useVlc = !isLiveTv && (!"ChannelVideoItem".equals(item.getType())) && TvApp.getApplication().getPrefs().getBoolean("pref_enable_vlc", true) && (item.getPath() == null || !item.getPath().toLowerCase().endsWith(".avi"));
                boolean useDirectProfile = transcodedSubtitle < 0 && useVlc;
                if (useVlc && item.getMediaSources() != null && item.getMediaSources().size() > 0) {
                    List<MediaStream> videoStreams = Utils.GetVideoStreams(item.getMediaSources().get(0));
                    MediaStream video = videoStreams != null && videoStreams.size() > 0 ? videoStreams.get(0) : null;
                    if (video != null && video.getWidth() > (Utils.isFireTvStick() ? 730 : Integer.parseInt(mApplication.getPrefs().getString("pref_vlc_max_res", "730")))) {
                        useDirectProfile = false;
                        useVlc = false;
                        mApplication.getLogger().Info("Forcing a transcode of HD content");
                    }
                } else {
                    useVlc = useVlc && !Utils.isFireTvStick();
                    useDirectProfile = useVlc;
                }

                AndroidProfile profile = useDirectProfile ? new AndroidProfile("vlc") : new AndroidProfile(Utils.getProfileOptions());
                mCurrentOptions.setProfile(profile);

                playInternal(getCurrentlyPlayingItem(), position, mCurrentOptions);
                mPlaybackState = PlaybackState.BUFFERING;
                if (mFragment != null) {
                    mFragment.setPlayPauseActionState(ImageButton.STATE_SECONDARY);
                    mFragment.setFadingEnabled(true);
                    mFragment.setCurrentTime(position);
                }

                long duration = getCurrentlyPlayingItem().getRunTimeTicks()!= null ? getCurrentlyPlayingItem().getRunTimeTicks() / 10000 : -1;
                mVideoManager.setMetaDuration(duration);

                if (hasNextItem()) {
                    // Determine the "next up" threshold
                    if (duration > 600000) {
                        //only items longer than 10min to have this feature
                        nextItemReported = false;
                        if (duration > 4500000) {
                            //longer than 1hr 15 it probably has pretty long credits
                            mNextItemThreshold = duration - 180000; // 3 min
                        } else {
                            //std 30 min episode or less
                            mNextItemThreshold = duration - 50000; // 50 seconds
                        }
                        TvApp.getApplication().getLogger().Debug("Next item threshold set to "+ mNextItemThreshold);
                    } else {
                        mNextItemThreshold = Long.MAX_VALUE;
                    }
                } else {
                    mNextItemThreshold = Long.MAX_VALUE;
                }

                break;
        }
    }

    public int getMaxBitrate() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(mApplication);
        String maxRate = sharedPref.getString("pref_max_bitrate", "0");
        Float factor = Float.parseFloat(maxRate) * 10;
        return factor == 0 ? TvApp.getApplication().getAutoBitrate() : (factor.intValue() * 100000);
    }

    public int getBufferAmount() {
        if (getCurrentlyPlayingItem() != null && getCurrentlyPlayingItem().getType().equals("TvChannel")) {
            // force live tv to a small buffer so it doesn't take forever to load
            mApplication.getLogger().Info("Forcing vlc buffer to 600 for live tv");
            return 600;
        }

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(TvApp.getApplication());
        String buffer = sharedPref.getString("pref_net_buffer", "30");
        Float factor = Float.parseFloat(buffer) * 10;
        return (factor.intValue() * 100);
    }

    private void playInternal(final BaseItemDto item, final long position, final VideoOptions options) {
        final ApiClient apiClient = mApplication.getApiClient();
        mApplication.setCurrentPlayingItem(item);
        if (isLiveTv) {
            liveTvChannelName = " ("+item.getName()+")";
            updateTvProgramInfo();
            TvManager.setLastLiveTvChannel(item.getId());
        }

        mApplication.getPlaybackManager().getVideoStreamInfo(apiClient.getServerInfo().getId(), options, false, apiClient, new Response<StreamInfo>() {
            @Override
            public void onResponse(StreamInfo response) {
                mCurrentStreamInfo = response;
                Long mbPos = position * 10000;

                setPlaybackMethod(response.getPlayMethod());

                if (useVlc && !getPlaybackMethod().equals(PlayMethod.Transcode)) {
                    mVideoManager.setNativeMode(false);
                } else {
                    mVideoManager.setNativeMode(true);
                    TvApp.getApplication().getLogger().Info("Playing back in native mode.");
                }

                // get subtitle info
                mSubtitleStreams = response.GetSubtitleProfiles(false, mApplication.getApiClient().getApiUrl(), mApplication.getApiClient().getAccessToken());

                mFragment.updateDisplay();
                String path = response.ToUrl(apiClient.getApiUrl(), apiClient.getAccessToken());

                mVideoManager.setVideoPath(path);
                mVideoManager.start();
                mStartPosition = position;

                mDefaultAudioIndex = mPlaybackMethod != PlayMethod.Transcode && response.getMediaSource().getDefaultAudioStreamIndex() != null ? response.getMediaSource().getDefaultAudioStreamIndex() : -1;
                mDefaultSubIndex = mPlaybackMethod != PlayMethod.Transcode && response.getMediaSource().getDefaultSubtitleStreamIndex() != null ? response.getMediaSource().getDefaultSubtitleStreamIndex() : -1;

                PlaybackStartInfo startInfo = new PlaybackStartInfo();
                startInfo.setItemId(item.getId());
                startInfo.setPositionTicks(mbPos);
                TvApp.getApplication().getPlaybackManager().reportPlaybackStart(startInfo, false, apiClient, new EmptyResponse());
                TvApp.getApplication().getLogger().Info("Playback of " + item.getName() + " started.");

            }

            @Override
            public void onError(Exception exception) {
                if (exception instanceof PlaybackException) {
                    PlaybackException ex = (PlaybackException) exception;
                    switch (ex.getErrorCode()) {
                        case NotAllowed:
                            Utils.showToast(TvApp.getApplication(), TvApp.getApplication().getString(R.string.msg_playback_not_allowed));
                            break;
                        case NoCompatibleStream:
                            Utils.showToast(TvApp.getApplication(), TvApp.getApplication().getString(R.string.msg_playback_incompatible));
                            break;
                        case RateLimitExceeded:
                            Utils.showToast(TvApp.getApplication(), TvApp.getApplication().getString(R.string.msg_playback_restricted));
                            break;
                    }
                }
            }
        });

    }

    public void startSpinner() {
        if (mApplication.getCurrentActivity() != null) {
            mApplication.getCurrentActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mSpinner != null) mSpinner.setVisibility(View.VISIBLE);
                    spinnerOff = false;
                }
            });

        }

    }

    public void stopSpinner() {
        if (mApplication.getCurrentActivity() != null) {
            mApplication.getCurrentActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    spinnerOff = true;
                    if (mSpinner != null) mSpinner.setVisibility(View.GONE);
                }
            });

        }

    }

    public void switchAudioStream(int index) {
        if (!isPlaying()) return;

        mCurrentOptions.setAudioStreamIndex(index);
        if (mCurrentStreamInfo.getPlayMethod() == PlayMethod.Transcode) {
            startSpinner();
            mApplication.getLogger().Debug("Setting audio index to: " + index);
            mCurrentOptions.setMediaSourceId(getCurrentMediaSource().getId());
            stop();
            playInternal(getCurrentlyPlayingItem(), mCurrentPosition, mCurrentOptions);
            mPlaybackState = PlaybackState.BUFFERING;
        } else {
            mVideoManager.setAudioTrack(index);
        }
    }

    private boolean burningSubs = false;

    public void switchSubtitleStream(int index) {
        mApplication.getLogger().Debug("Setting subtitle index to: " + index);
        mCurrentOptions.setSubtitleStreamIndex(index >= 0 ? index : null);

        if (index < 0) {
            if (burningSubs) {
                stop();
                play(mCurrentPosition);
                burningSubs = false;
            } else {
                mFragment.addManualSubtitles(null);
                mVideoManager.disableSubs();
            }
            return;
        }

        MediaStream stream = Utils.GetMediaStream(getCurrentMediaSource(), index);
        if (stream == null) {
            Utils.showToast(mApplication, "Unable to select subtitle");
            return;
        }


        // handle according to delivery method
        SubtitleStreamInfo streamInfo = getSubtitleStreamInfo(index);
        if (streamInfo == null) {
            Utils.showToast(mApplication, mApplication.getResources().getString(R.string.msg_unable_load_subs));
        } else {
            switch (streamInfo.getDeliveryMethod()) {

                case Encode:
                    // Gonna need to burn in so start a transcode with the sub index
                    stop();
                    Utils.showToast(mApplication, mApplication.getResources().getString(R.string.msg_burn_sub_warning));
                    play(mCurrentPosition, index);
                    break;
                case Embed:
                    if (!mVideoManager.isNativeMode()) {
                        mFragment.addManualSubtitles(null); // in case these were on
                        if (!mVideoManager.setSubtitleTrack(index, getCurrentlyPlayingItem().getMediaStreams())) {
                            // error selecting internal subs
                            Utils.showToast(mApplication, mApplication.getResources().getString(R.string.msg_unable_load_subs));
                        }
                        break;
                    }
                    // not using vlc - fall through to external handling
                case External:
                    mFragment.addManualSubtitles(null);
                    mVideoManager.disableSubs();
                    mFragment.showSubLoadingMsg(true);
                    stream.setDeliveryMethod(SubtitleDeliveryMethod.External);
                    stream.setDeliveryUrl(String.format("%1$s/Videos/%2$s/%3$s/Subtitles/%4$s/0/Stream.JSON", mApplication.getApiClient().getApiUrl(), mCurrentStreamInfo.getItemId(), mCurrentStreamInfo.getMediaSourceId(), StringHelper.ToStringCultureInvariant(stream.getIndex())));
                    mApplication.getApiClient().getSubtitles(stream.getDeliveryUrl(), new Response<SubtitleTrackInfo>() {

                        @Override
                        public void onResponse(final SubtitleTrackInfo info) {

                            if (info != null) {
                                TvApp.getApplication().getLogger().Debug("Adding json subtitle track to player");
                                mFragment.addManualSubtitles(info);
                            } else {
                                TvApp.getApplication().getLogger().Error("Empty subtitle result");
                                Utils.showToast(mApplication, mApplication.getResources().getString(R.string.msg_unable_load_subs));
                                mFragment.showSubLoadingMsg(false);
                            }
                        }

                        @Override
                        public void onError(Exception ex) {
                            TvApp.getApplication().getLogger().ErrorException("Error downloading subtitles", ex);
                            Utils.showToast(mApplication, mApplication.getResources().getString(R.string.msg_unable_load_subs));
                            mFragment.showSubLoadingMsg(false);
                        }

                    });
                    break;
                case Hls:
                    break;
            }

        }

    }


    public void pause() {
        mPlaybackState = PlaybackState.PAUSED;
        mVideoManager.pause();
        if (mFragment != null) {
            mFragment.setFadingEnabled(false);
            mFragment.setPlayPauseActionState(ImageButton.STATE_PRIMARY);
        }

        stopReportLoop();
        // call once more to be sure everything up to date
        Utils.ReportProgress(getCurrentlyPlayingItem(), getCurrentStreamInfo(), mVideoManager.getCurrentPosition() * 10000, true);

    }

    public void playPause() {
        switch (mPlaybackState) {
            case PLAYING:
                pause();
                break;
            case PAUSED:
            case IDLE:
                play(getCurrentPosition());
                break;
        }
    }

    public void stop() {
        if (mPlaybackState != PlaybackState.IDLE && mPlaybackState != PlaybackState.UNDEFINED) {
            mPlaybackState = PlaybackState.IDLE;
            stopReportLoop();
            if (mVideoManager.isPlaying()) mVideoManager.stopPlayback();
            //give it a just a beat to actually stop - this keeps it from re-requesting the stream after we tell the server we've stopped
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Long mbPos = mCurrentPosition * 10000;
            Utils.ReportStopped(getCurrentlyPlayingItem(), getCurrentStreamInfo(), mbPos);
            // be sure to unmute audio in case it was muted
            TvApp.getApplication().setAudioMuted(false);

        }
    }

    public void next() {
        mApplication.getLogger().Debug("Next called.");
        if (mCurrentIndex < mItems.size() - 1) {
            stop();
            mCurrentIndex++;
            mApplication.getLogger().Debug("Moving to index: " + mCurrentIndex + " out of " + mItems.size() + " total items.");
            mFragment.removeQueueItem(0);
            spinnerOff = false;
            play(0);
        }
    }

    public void prev() {

    }

    public void seek(final long pos) {
        mApplication.getLogger().Debug("Seeking to " + pos);
        if (mVideoManager.seekTo(pos) >= 0)
        {
            if (mFragment != null) {
                mFragment.updateEndTime(mVideoManager.getDuration() - pos);
            }
        } else {
            Utils.showToast(TvApp.getApplication(), "Unable to seek");
        }

    }

    private long currentSkipAmt = 0;
    private Runnable skipRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying()) return; // in case we completed since this was requested

            seek(mVideoManager.getCurrentPosition() + currentSkipAmt);
            currentSkipAmt = 0;
            updateProgress = true; // re-enable true progress updates
        }
    };

    private float playSpeed = 1;
    public void togglePlaySpeed() {
        if (playSpeed < 4) playSpeed += 1f;
        else playSpeed = 1;
        mVideoManager.setPlaySpeed(playSpeed);
    }

    public void skip(int msec) {
        if (isPlaying()) {
            mHandler.removeCallbacks(skipRunnable);
            stopReportLoop();
            currentSkipAmt += msec;
            updateProgress = false; // turn this off so we can show where it will be jumping to
            mFragment.setCurrentTime(mVideoManager.getCurrentPosition() + currentSkipAmt);
            mHandler.postDelayed(skipRunnable, 800);
        }
    }

    private void updateTvProgramInfo() {
        // Get the current program info when playing a live TV channel
        final BaseItemDto channel = getCurrentlyPlayingItem();
        if (channel.getType().equals("TvChannel")) {
            TvApp.getApplication().getApiClient().GetLiveTvChannelAsync(channel.getId(), TvApp.getApplication().getCurrentUser().getId(), new Response<ChannelInfoDto>() {
                @Override
                public void onResponse(ChannelInfoDto response) {
                    BaseItemDto program = response.getCurrentProgram();
                    if (program != null) {
                        channel.setName(program.getName() + liveTvChannelName);
                        channel.setPremiereDate(program.getStartDate());
                        channel.setEndDate(program.getEndDate());
                        channel.setOfficialRating(program.getOfficialRating());
                        channel.setRunTimeTicks(program.getRunTimeTicks());
                        mCurrentProgramEndTime = channel.getEndDate() != null ? Utils.convertToLocalDate(channel.getEndDate()).getTime() : 0;
                        mCurrentProgramStartTime = channel.getPremiereDate() != null ? Utils.convertToLocalDate(channel.getPremiereDate()).getTime() : 0;
                        mFragment.updateDisplay();
                    }
                }
            });

        }
    }

    private long getRealTimeProgress() {
        return System.currentTimeMillis() - mCurrentProgramStartTime;
    }

    private void startReportLoop() {
        Utils.ReportProgress(getCurrentlyPlayingItem(), getCurrentStreamInfo(), mVideoManager.getCurrentPosition() * 10000, false);
        mReportLoop = new Runnable() {
            @Override
            public void run() {
                if (mPlaybackState == PlaybackState.PLAYING) {
                    long currentTime = mVideoManager.getCurrentPosition();

                    Utils.ReportProgress(getCurrentlyPlayingItem(), getCurrentStreamInfo(), currentTime * 10000, false);

                    //Do this next up processing here because every 3 seconds is good enough
                    if (!nextItemReported && hasNextItem() && currentTime >= mNextItemThreshold){
                        nextItemReported = true;
                        mFragment.nextItemThresholdHit(getNextItem());
                    }
                }
                mApplication.setLastUserInteraction(System.currentTimeMillis());
                if (mPlaybackState != PlaybackState.UNDEFINED && mPlaybackState != PlaybackState.IDLE) mHandler.postDelayed(this, REPORT_INTERVAL);
            }
        };
        mHandler.postDelayed(mReportLoop, REPORT_INTERVAL);
    }

    private void stopReportLoop() {
        if (mHandler != null && mReportLoop != null) {
            mHandler.removeCallbacks(mReportLoop);

        }

    }

    private void delayedSeek(final long position) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mVideoManager.getDuration() <= 0) {
                    // wait until we have valid duration
                    mHandler.postDelayed(this, 25);
                } else {
                    // do the seek
                    if (mVideoManager.seekTo(position) < 0)
                        Utils.showToast(TvApp.getApplication(), "Unable to seek");

                    mPlaybackState = PlaybackState.PLAYING;
                    updateProgress = true;
                    mFragment.updateEndTime(mVideoManager.getDuration() - position);
                }
            }
        });
    }



    private void itemComplete() {
        mPlaybackState = PlaybackState.IDLE;
        stopReportLoop();
        Long mbPos = mVideoManager.getCurrentPosition() * 10000;
        Utils.ReportStopped(getCurrentlyPlayingItem(), getCurrentStreamInfo(), mbPos);
        if (mCurrentIndex < mItems.size() - 1) {
            // move to next in queue
            mCurrentIndex++;
            mApplication.getLogger().Debug("Moving to next queue item. Index: "+mCurrentIndex);
            mFragment.removeQueueItem(0);
            spinnerOff = false;
            play(0);
        } else {
            // exit activity
            mApplication.getLogger().Debug("Last item completed. Finishing activity.");
            mFragment.finish();
        }
    }

    private void setupCallbacks() {

        mVideoManager.setOnErrorListener(new PlaybackListener() {

            @Override
            public void onEvent() {
                String msg =  mApplication.getString(R.string.video_error_unknown_error);
                Utils.showToast(mApplication, mApplication.getString(R.string.msg_video_playback_error) + msg);
                mApplication.getLogger().Error("Playback error - " + msg);
                mPlaybackState = PlaybackState.ERROR;
                stop();

            }
        });


        mVideoManager.setOnPreparedListener(new PlaybackListener() {
            @Override
            public void onEvent() {

                if (mPlaybackState == PlaybackState.BUFFERING) {
                    mPlaybackState = PlaybackState.PLAYING;
                    mFragment.updateEndTime(mVideoManager.getDuration() - mStartPosition);
                    startReportLoop();
                }
                TvApp.getApplication().getLogger().Info("Play method: ", mCurrentStreamInfo.getPlayMethod());

                if (mDefaultSubIndex >= 0) {
                    //Default subs requested select them
                    mApplication.getLogger().Info("Selecting default sub stream: " + mDefaultSubIndex);
                    switchSubtitleStream(mDefaultSubIndex);
                } else {
                    TvApp.getApplication().getLogger().Info("Turning off subs by default");
                    mVideoManager.disableSubs();
                }

                if (mDefaultAudioIndex >= 0) {
                    TvApp.getApplication().getLogger().Info("Selecting default audio stream: "+mDefaultAudioIndex);
                    switchAudioStream(mDefaultAudioIndex);
                }


            }
        });

        mVideoManager.setOnProgressListener(new PlaybackListener() {
            @Override
            public void onEvent() {
                if (isPlaying() && updateProgress) {
                    updateProgress = false;
                    boolean continueUpdate = true;
                    if (!spinnerOff) {
                        if (mStartPosition > 0) {
                            mPlaybackState = PlaybackState.SEEKING;
                            delayedSeek(mStartPosition);
                            continueUpdate = false;
                            mStartPosition = 0;
                        } else {
                            stopSpinner();
                        }
                        if (getPlaybackMethod() != PlayMethod.Transcode) {
                            if (mCurrentOptions.getAudioStreamIndex() != null) mVideoManager.setAudioTrack(mCurrentOptions.getAudioStreamIndex());
                        }
                    }
                    if (continueUpdate) {
                        mApplication.setLastUserInteraction(System.currentTimeMillis()); // don't want to auto logoff during playback
                        if (isLiveTv && mCurrentProgramEndTime > 0 && System.currentTimeMillis() >= mCurrentProgramEndTime) {
                            // crossed fire off an async routine to update the program info
                            updateTvProgramInfo();
                        }
                        final Long currentTime = isLiveTv && mCurrentProgramStartTime > 0 ? getRealTimeProgress() : mVideoManager.getCurrentPosition();
                        mFragment.setCurrentTime(currentTime);
                        mCurrentPosition = currentTime;
                        mFragment.updateSubtitles(currentTime);
                    }

                    updateProgress = continueUpdate;
                }
            }
        });

        mVideoManager.setOnCompletionListener(new PlaybackListener() {
            @Override
            public void onEvent() {
                TvApp.getApplication().getLogger().Debug("On Completion fired");
                itemComplete();
            }
        });

    }

    public long getCurrentPosition() {
        return mCurrentPosition;
    }

    public boolean isPaused() {
        return mPlaybackState == PlaybackState.PAUSED;
    }

    public boolean isIdle() {
        return mPlaybackState == PlaybackState.IDLE;
    }


    /*
 * List of various states that we can be in
 */
    public enum PlaybackState {
        PLAYING, PAUSED, BUFFERING, IDLE, SEEKING, UNDEFINED, ERROR;
    }

}
