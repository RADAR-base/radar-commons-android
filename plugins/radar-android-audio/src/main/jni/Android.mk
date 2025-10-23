LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

############ you might need to change this if you move the
############ android studio project somewhere else, i.e.
############ outside of the opensmile root folder
OPENSMILE_ROOT := $(LOCAL_PATH)/opensmile-2.3.0
##########################################################

LOCAL_MODULE	:= smile_jni
LOCAL_SRC_FILES := opensmile-2.3.0/src/video/openCVSource.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/smileutil/smileUtil.c
LOCAL_SRC_FILES += opensmile-2.3.0/src/smileutil/smileUtilSpline.c
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/fftsg.c
LOCAL_SRC_FILES += opensmile-2.3.0/src/rnn/rnnSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/rnn/rnnProcessor.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/rnn/rnnVad2.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/rnn/rnn.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/pitchSmoother.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/mzcr.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/plp.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/pitchACF.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/pitchBase.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/energy.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/mfcc.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/spectral.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/melspec.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lldcore/intensity.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/datadumpSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/htkSource.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/arffSource.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/csvSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/csvSource.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/waveSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/htkSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/arffSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/waveSource.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/iocore/waveSinkCut.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalPeaks.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalSamples.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalPercentiles.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalLpc.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalExtremes.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalSegments.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionals.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalComponent.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalRegression.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalMoments.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalOnset.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalTimes.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalPeaks2.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalCrossings.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalDCT.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalModulation.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/functionals/functionalMeans.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/formantSmoother.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/pitchSmootherViterbi.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/pitchShs.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/cens.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/pitchDirection.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/tonefilt.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/tonespec.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/formantLpc.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/lsp.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/harmonics.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/lpc.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/pitchJitter.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/lld/chroma.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/smileutil/zerosolve.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/other/bowProducer.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/other/valbasedSelector.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/other/maxIndex.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/other/vectorOperation.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/other/vectorConcat.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/smileComponent.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/dataMemory.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/dataReader.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/dataSource.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/dataWriter.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/dataSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/windowProcessor.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/exceptions.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/commandlineParser.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/vectorTransform.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/smileLogger.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/winToVecProcessor.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/vecToWinProcessor.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/componentManager.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/dataSelector.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/dataProcessor.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/vectorProcessor.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/configManager.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/smileCommon.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/core/nullSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/io/libsvmSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/classifiers/julius/juliusSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/classifiers/libsvmliveSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/classifiers/svmSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/classifiers/libsvm/svm.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dsp/vadV1.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dsp/specResample.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dsp/dbA.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dsp/signalGenerator.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dsp/specScale.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dsp/smileResample.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/acf.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/deltaRegression.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/transformFft.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/preemphasis.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/fullinputMean.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/vectorPreemphasis.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/fftmagphase.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/amdf.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/contourSmoother.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/monoMixdown.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/turnDetector.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/fullturnMean.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/vectorMVN.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/windower.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/dspcore/framer.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/portaudio/portaudioWavplayer.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/portaudio/portaudioSource.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/portaudio/portaudioDuplex.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/portaudio/portaudioSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/android/openslesSource.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/android/jniMessageInterface.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/examples/exampleSink.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/examples/exampleSource.cpp
LOCAL_SRC_FILES += opensmile-2.3.0/src/examples/simpleMessageSender.cpp
LOCAL_SRC_FILES += smile_jni.cpp
LOCAL_SRC_FILES += smilextract.cpp

LOCAL_CPP_FEATURES := exceptions

DEFINES := -DOPENSMILE_BUILD -DBUILD_RNN -DHAVE_OPENSLES
OPTIMIZE := -g -O3 -ffast-math -ftree-vectorize -funsafe-math-optimizations -fvisibility=hidden -ffunction-sections -fdata-sections
LOCAL_LDFLAGS += -Wl,--gc-sections
LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384"

LOCAL_CFLAGS := $(OPTIMIZE) -I$(OPENSMILE_ROOT)/src/include $(DEFINES)
LOCAL_CPPFLAGS := $(OPTIMIZE) -I$(OPENSMILE_ROOT)/src/include $(DEFINES)

LOCAL_LDLIBS := -lm -ldl -llog -D__STDC_CONSTANT_MACROS -lc
LOCAL_LDLIBS += -lOpenSLES

include $(BUILD_SHARED_LIBRARY)
