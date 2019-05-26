package com.reactlibrary.player;

import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Pair;
import android.view.Surface;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.C.Encoding;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.RawResourceDataSource;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.util.Util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import android.util.Log;

import java.io.IOException;

import com.reactlibrary.AVModule;


class StereoPanAudioProcessor implements AudioProcessor {

  private int channelCount;
  private int sampleRateHz;
  private int[] pendingOutputChannels;

  private boolean active;
  private int[] outputChannels;
  private short[] outputChannelVolumes;
  private ByteBuffer buffer;
  private ByteBuffer outputBuffer;
  private boolean inputEnded;

  /**
   * Creates a new processor that applies a channel mapping.
   */
  public StereoPanAudioProcessor() {
    buffer = EMPTY_BUFFER;
    outputBuffer = EMPTY_BUFFER;
    channelCount = Format.NO_VALUE;
    sampleRateHz = Format.NO_VALUE;
    outputChannelVolumes = new short[] {1,1};
  }

  /**
   * Resets the channel mapping. After calling this method, call {@link #configure(int, int, int)}
   * to start using the new channel map.
   *
   * @see AudioSink#configure(int, int, int, int, int[], int, int)
   */
  public void setChannelMap(int[] outputChannels) {
    pendingOutputChannels = outputChannels;
  }

  public short[] setPanning(int panMode ){
    if(panMode == -1){
      outputChannelVolumes = new short[] {1,0};
      return outputChannelVolumes;
    }
    if(panMode == 1){
      outputChannelVolumes = new short[] {0,1}; 
      return outputChannelVolumes;
    }
    outputChannelVolumes = new short[] {1,1};
    return outputChannelVolumes;
  }
  @Override
  public boolean configure(int sampleRateHz, int channelCount, @Encoding int encoding)
      throws UnhandledFormatException {
    boolean outputChannelsChanged = !Arrays.equals(pendingOutputChannels, outputChannels);
    outputChannels = pendingOutputChannels;
    if (outputChannels == null) {
      active = false;
      return outputChannelsChanged;
    }
    if (encoding != C.ENCODING_PCM_16BIT) {
      throw new UnhandledFormatException(sampleRateHz, channelCount, encoding);
    }
    if (!outputChannelsChanged && this.sampleRateHz == sampleRateHz
        && this.channelCount == channelCount) {
      return false;
    }
    this.sampleRateHz = sampleRateHz;
    this.channelCount = channelCount;

    active = channelCount != outputChannels.length;
    for (int i = 0; i < outputChannels.length; i++) {
      int channelIndex = outputChannels[i];
      if (channelIndex >= channelCount) {
        throw new UnhandledFormatException(sampleRateHz, channelCount, encoding);
      }
      active |= (channelIndex != i);
    }
    return true;
  }

  @Override
  public boolean isActive() {
    return true;
  }

  @Override
  public int getOutputChannelCount() {
    return outputChannels == null ? channelCount : outputChannels.length;
  }

  @Override
  public int getOutputEncoding() {
    return C.ENCODING_PCM_16BIT;
  }

  @Override
  public int getOutputSampleRateHz() {
    return sampleRateHz;
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    int position = inputBuffer.position();
    int limit = inputBuffer.limit();
    int frameCount = (limit - position) / (2 * channelCount);
    int outputSize = frameCount * outputChannels.length * 2;
    if (buffer.capacity() < outputSize) {
      buffer = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder());
    } else {
      buffer.clear();
    }
    while (position < limit) {
      for (int channelIndex : outputChannels) {
        short sample = inputBuffer.getShort(position + 2 * channelIndex);
        short channelVol = outputChannelVolumes[channelIndex];
        short pannedSample = (short) (sample * channelVol);
        buffer.putShort(pannedSample);
      }
      position += channelCount * 2;
    }
    inputBuffer.position(limit);
    buffer.flip();
    outputBuffer = buffer;
  }

  @Override
  public void queueEndOfStream() {
    inputEnded = true;
  }

  @Override
  public ByteBuffer getOutput() {
    ByteBuffer outputBuffer = this.outputBuffer;
    this.outputBuffer = EMPTY_BUFFER;
    return outputBuffer;
  }

  @SuppressWarnings("ReferenceEquality")
  @Override
  public boolean isEnded() {
    return inputEnded && outputBuffer == EMPTY_BUFFER;
  }

  @Override
  public void flush() {
    outputBuffer = EMPTY_BUFFER;
    inputEnded = false;
  }

  @Override
  public void reset() {
    flush();
    buffer = EMPTY_BUFFER;
    channelCount = Format.NO_VALUE;
    sampleRateHz = Format.NO_VALUE;
    outputChannels = null;
    active = false;
  }

}

class SimpleExoPlayerData extends PlayerData
    implements Player.EventListener, ExtractorMediaSource.EventListener, AdaptiveMediaSourceEventListener {

  private static final String IMPLEMENTATION_NAME = "SimpleExoPlayer";

  private SimpleExoPlayer mSimpleExoPlayer = null;
  private StereoPanAudioProcessor mappingAudioProcessor;
  private String mOverridingExtension;
  private LoadCompletionListener mLoadCompletionListener = null;
  private Integer mLastPlaybackState = null;
  private boolean mIsLooping = false;
  private boolean mIsLoading = true;
  private ReactContext mReactContext;

  private static final String TAG = "PakExo";
  // Measures bandwidth during playback. Can be null if not required.
  private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();

  SimpleExoPlayerData(final AVModule avModule, final ReactContext context, final Uri uri, final String overridingExtension) {
    super(avModule, uri);
    mReactContext = context;
    mOverridingExtension = overridingExtension;
  }

  @Override
  String getImplementationName() {
    return IMPLEMENTATION_NAME;
  }

  // --------- PlayerData implementation ---------

  // Lifecycle

  @Override
  public void load(final ReadableMap status, final LoadCompletionListener loadCompletionListener) {
    mLoadCompletionListener = loadCompletionListener;

    // Create a default TrackSelector
    final Handler mainHandler = new Handler();
    final TrackSelection.Factory trackSelectionFactory = new AdaptiveTrackSelection.Factory(BANDWIDTH_METER);
    final TrackSelector trackSelector = new DefaultTrackSelector(trackSelectionFactory);
    mappingAudioProcessor = new StereoPanAudioProcessor();
    // mappingAudioProcessor.setPanning(1);
    RenderersFactory renderersFactory = new DefaultRenderersFactory(mReactContext) {
      @Override
      public AudioProcessor[] buildAudioProcessors() {
        mappingAudioProcessor.setChannelMap(new int[] {0,1});
        Log.d(TAG, "Setup using my StereoPanAudioProcessor ! ");
        return new AudioProcessor[] {mappingAudioProcessor};
      }
    };
    // Create the player
    mSimpleExoPlayer = ExoPlayerFactory.newSimpleInstance(renderersFactory, trackSelector);
    mSimpleExoPlayer.addListener(this);
    final MediaSource source;
    if (mUri.getScheme().equals("file") ) {
       DataSpec dataSpec = new DataSpec(mUri);
      final FileDataSource fileDataSource = new FileDataSource();
      try {
          fileDataSource.open(dataSpec);
      } catch (FileDataSource.FileDataSourceException e) {
          e.printStackTrace();
      }
      DataSource.Factory factory = new DataSource.Factory() {
          @Override
          public DataSource createDataSource() {
              return fileDataSource;
          }
      };
      source = new ExtractorMediaSource(fileDataSource.getUri(),
                factory, new DefaultExtractorsFactory(), null, null);
    } else {
      // Produces DataSource instances through which media data is loaded.
      final DataSource.Factory dataSourceFactory = new SharedCookiesDataSourceFactory(mUri, mReactContext, Util.getUserAgent(mReactContext, "yourApplicationName"));
      // This is the MediaSource representing the media to be played.
      source = buildMediaSource(mUri, mOverridingExtension, mainHandler, dataSourceFactory);
    }
   
    try {
      mSimpleExoPlayer.prepare(source);
      setStatus(status, null);
    }  catch (IllegalStateException e) {
      Log.d(TAG, "pak exception when mSimpleExoPlayer.prepare");
      onFatalError(e);
    }
    //   // Prepare the player with the source.
    //   mSimpleExoPlayer.prepare(source);
    //   setStatus(status, null);
    // } catch (IllegalStateException e) {
    //   Log.d(TAG, "pak exception when mSimpleExoPlayer.prepare");
    //   onFatalError(e);
    // }
  }

  @Override
  public synchronized void release() {
    if (mappingAudioProcessor != null){
      mappingAudioProcessor = null;
    }
    if (mSimpleExoPlayer != null) {
      mSimpleExoPlayer.release();
      mSimpleExoPlayer = null;
    }
  }

  @Override
  boolean shouldContinueUpdatingProgress() {
    return mSimpleExoPlayer != null && mSimpleExoPlayer.getPlayWhenReady();
  }

  // Set status

  @Override
  void playPlayerWithRateAndMuteIfNecessary() throws AVModule.AudioFocusNotAcquiredException {
    if (mSimpleExoPlayer == null || !shouldPlayerPlay()) {
      return;
    }

    if (!mIsMuted) {
      mAVModule.acquireAudioFocus();
    }

    updateVolumeMuteAndDuck();

    mSimpleExoPlayer.setPlaybackParameters(new PlaybackParameters(mRate, mShouldCorrectPitch ? 1.0f : mRate));

    mSimpleExoPlayer.setPlayWhenReady(mShouldPlay);

    beginUpdatingProgressIfNecessary();
  }

  @Override
  void applyNewStatus(final Integer newPositionMillis, final Boolean newIsLooping)
      throws AVModule.AudioFocusNotAcquiredException, IllegalStateException {
    //pak here is an error!
    if (mSimpleExoPlayer == null) {
      throw new IllegalStateException("mSimpleExoPlayer is null!");
    }

    // Set looping idempotently
    if (newIsLooping != null) {
      mIsLooping = newIsLooping;
      if (mIsLooping) {
        mSimpleExoPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
      } else {
        mSimpleExoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
      }
    }

    // Pause first if necessary.
    if (!shouldPlayerPlay()) {
      mSimpleExoPlayer.setPlayWhenReady(false);
      stopUpdatingProgressIfNecessary();
    }
     Log.d(TAG, "applyNewStatus setPanning" + mPan);
    mappingAudioProcessor.setPanning((int) mPan);
    // Mute / update volume if it doesn't require a request of the audio focus.
    updateVolumeMuteAndDuck();
    // Seek
    if (newPositionMillis != null) {
      // TODO handle different timeline cases for streaming
      mSimpleExoPlayer.seekTo(newPositionMillis);
    }

    // Play / unmute
    playPlayerWithRateAndMuteIfNecessary();
  }

  // Get status

  @Override
  boolean isLoaded() {
    return mSimpleExoPlayer != null;
  }

  @Override
  void getExtraStatusFields(final WritableMap map) {
    // TODO handle different timeline cases for streaming
    final int duration = (int) mSimpleExoPlayer.getDuration();
    map.putInt(STATUS_DURATION_MILLIS_KEY_PATH, duration);
    map.putInt(STATUS_POSITION_MILLIS_KEY_PATH,
        getClippedIntegerForValue((int) mSimpleExoPlayer.getCurrentPosition(), 0, duration));
    map.putInt(STATUS_PLAYABLE_DURATION_MILLIS_KEY_PATH,
        getClippedIntegerForValue((int) mSimpleExoPlayer.getBufferedPosition(), 0, duration));

    map.putBoolean(STATUS_IS_PLAYING_KEY_PATH,
        mSimpleExoPlayer.getPlayWhenReady() && mSimpleExoPlayer.getPlaybackState() == Player.STATE_READY);
    map.putBoolean(STATUS_IS_BUFFERING_KEY_PATH,
        mIsLoading || mSimpleExoPlayer.getPlaybackState() == Player.STATE_BUFFERING);

    map.putBoolean(STATUS_IS_LOOPING_KEY_PATH, mIsLooping);
  }

  @Override
  public int getAudioSessionId() {
    return mSimpleExoPlayer != null ? mSimpleExoPlayer.getAudioSessionId() : 0;
  }

  // --------- Interface implementation ---------

  // AudioEventHandler

  @Override
  public void pauseImmediately() {
    if (mSimpleExoPlayer != null) {
      mSimpleExoPlayer.setPlayWhenReady(false);
    }
    stopUpdatingProgressIfNecessary();
  }

  @Override
  public boolean requiresAudioFocus() {
    return mSimpleExoPlayer != null && (mSimpleExoPlayer.getPlayWhenReady() || shouldPlayerPlay()) && !mIsMuted;
  }

  @Override
  public void updateVolumeMuteAndDuck() {
    if (mSimpleExoPlayer != null) {
      mSimpleExoPlayer.setVolume(mAVModule.getVolumeForDuckAndFocus(mIsMuted, mVolume));
    }
  }

  // ExoPlayer.EventListener

  @Override
  public void onLoadingChanged(final boolean isLoading) {
    mIsLoading = isLoading;
    callStatusUpdateListener();
  }

  @Override
  public void onPlaybackParametersChanged(PlaybackParameters parameters) {
  }

  @Override
  public void onSeekProcessed() {

  }

  @Override
  public void onRepeatModeChanged(int repeatMode) {
  }

  @Override
  public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {

  }

  @Override
  public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {

  }

  @Override
  public void onTracksChanged(TrackGroupArray trackGroups,
                              TrackSelectionArray trackSelections) {

  }

  @Override
  public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
    if (playbackState == Player.STATE_READY && mLoadCompletionListener != null) {
      final LoadCompletionListener listener = mLoadCompletionListener;
      mLoadCompletionListener = null;
      listener.onLoadSuccess(getStatus());
    }

    if (mLastPlaybackState != null
        && playbackState != mLastPlaybackState
        && playbackState == Player.STATE_ENDED) {
      callStatusUpdateListenerWithDidJustFinish();
    } else {
      callStatusUpdateListener();
    }
    mLastPlaybackState = playbackState;
  }



  @Override
  public void onPlayerError(final ExoPlaybackException error) {
    mErrorListener.onError("Player error: " + error.getMessage());
  }

  @Override
  public void onPositionDiscontinuity(int reason) {

  }

  // ExtractorMediaSource.EventListener

  @Override
  public void onLoadError(final IOException error) {
    onFatalError(error);
  }

  private void onFatalError(final Exception error) {
    Log.d(TAG, "onFatalError: " + error);

    if (mLoadCompletionListener != null) {
      final LoadCompletionListener listener = mLoadCompletionListener;
      mLoadCompletionListener = null;
      listener.onLoadError(error.toString());
    }
    release();
  }

  // https://github.com/google/ExoPlayer/blob/2b20780482a9c6b07416bcbf4de829532859d10a/demos/main/src/main/java/com/google/android/exoplayer2/demo/PlayerActivity.java#L365-L393
  private MediaSource buildMediaSource(Uri uri, String overrideExtension, Handler mainHandler, DataSource.Factory factory) {
    @C.ContentType int type = TextUtils.isEmpty(overrideExtension) ? Util.inferContentType(String.valueOf(uri)) : Util.inferContentType("." + overrideExtension);

    switch (type) {
      case C.TYPE_SS:
        return new SsMediaSource(uri, factory,
            new DefaultSsChunkSource.Factory(factory), mainHandler, this);
      case C.TYPE_DASH:
        return new DashMediaSource(uri, factory,
            new DefaultDashChunkSource.Factory(factory), mainHandler, this);
      case C.TYPE_HLS:
        return new HlsMediaSource(uri, factory, mainHandler, this);
      case C.TYPE_OTHER://this one is ours 3
        return new ExtractorMediaSource(uri, factory, new DefaultExtractorsFactory(), mainHandler, this);
      default: {
        throw new IllegalStateException("Content of this type is unsupported at the moment. Unsupported type: " + type);
      }
    }
  }

  // AdaptiveMediaSourceEventListener

  @Override
  public void onLoadStarted(DataSpec dataSpec, int dataType, int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs) {

  }

  @Override
  public void onLoadCompleted(DataSpec dataSpec, int dataType, int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs, long bytesLoaded) {

  }

  @Override
  public void onLoadCanceled(DataSpec dataSpec, int dataType, int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs, long bytesLoaded) {

  }

  @Override
  public void onLoadError(DataSpec dataSpec, int dataType, int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs, long bytesLoaded, IOException error, boolean wasCanceled) {
    onLoadError(error);
  }

  @Override
  public void onUpstreamDiscarded(int trackType, long mediaStartTimeMs, long mediaEndTimeMs) {

  }

  @Override
  public void onDownstreamFormatChanged(int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaTimeMs) {

  }
}
