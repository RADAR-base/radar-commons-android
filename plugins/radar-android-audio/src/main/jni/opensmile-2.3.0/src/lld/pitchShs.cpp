/*F***************************************************************************
 * 
 * openSMILE - the Munich open source Multimedia Interpretation by 
 * Large-scale Extraction toolkit
 * 
 * This file is part of openSMILE.
 * 
 * openSMILE is copyright (c) by audEERING GmbH. All rights reserved.
 * 
 * See file "COPYING" for details on usage rights and licensing terms.
 * By using, copying, editing, compiling, modifying, reading, etc. this
 * file, you agree to the licensing terms in the file COPYING.
 * If you do not agree to the licensing terms,
 * you must immediately destroy all copies of this file.
 * 
 * THIS SOFTWARE COMES "AS IS", WITH NO WARRANTIES. THIS MEANS NO EXPRESS,
 * IMPLIED OR STATUTORY WARRANTY, INCLUDING WITHOUT LIMITATION, WARRANTIES OF
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE, ANY WARRANTY AGAINST
 * INTERFERENCE WITH YOUR ENJOYMENT OF THE SOFTWARE OR ANY WARRANTY OF TITLE
 * OR NON-INFRINGEMENT. THERE IS NO WARRANTY THAT THIS SOFTWARE WILL FULFILL
 * ANY OF YOUR PARTICULAR PURPOSES OR NEEDS. ALSO, YOU MUST PASS THIS
 * DISCLAIMER ON WHENEVER YOU DISTRIBUTE THE SOFTWARE OR DERIVATIVE WORKS.
 * NEITHER TUM NOR ANY CONTRIBUTOR TO THE SOFTWARE WILL BE LIABLE FOR ANY
 * DAMAGES RELATED TO THE SOFTWARE OR THIS LICENSE AGREEMENT, INCLUDING
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL OR INCIDENTAL DAMAGES, TO THE
 * MAXIMUM EXTENT THE LAW PERMITS, NO MATTER WHAT LEGAL THEORY IT IS BASED ON.
 * ALSO, YOU MUST PASS THIS LIMITATION OF LIABILITY ON WHENEVER YOU DISTRIBUTE
 * THE SOFTWARE OR DERIVATIVE WORKS.
 * 
 * Main authors: Florian Eyben, Felix Weninger, 
 * 	      Martin Woellmer, Bjoern Schuller
 * 
 * Copyright (c) 2008-2013, 
 *   Institute for Human-Machine Communication,
 *   Technische Universitaet Muenchen, Germany
 * 
 * Copyright (c) 2013-2015, 
 *   audEERING UG (haftungsbeschraenkt),
 *   Gilching, Germany
 * 
 * Copyright (c) 2016,	 
 *   audEERING GmbH,
 *   Gilching Germany
 ***************************************************************************E*/


/*  openSMILE component:

Input log2 scale spectrum

*/



#include <lld/pitchShs.hpp>

#define MODULE "cPitchShs"


SMILECOMPONENT_STATICS(cPitchShs)

SMILECOMPONENT_REGCOMP(cPitchShs)
{
  SMILECOMPONENT_REGCOMP_INIT
  scname = COMPONENT_NAME_CPITCHSHS;
  sdescription = COMPONENT_DESCRIPTION_CPITCHSHS;

  // we inherit cPitchBase configType and extend it:
  SMILECOMPONENT_INHERIT_CONFIGTYPE("cPitchBase")

  const ConfigType *r2 = sconfman->getTypeObj("cDataWriter");
  if (r2 == NULL) {
    SMILE_WRN(4,"%s config Type not found!","configtype");
    rA=1;
  } else {
    ConfigType * ct2 = new ConfigType( *(r2) , "cDataWriterShs" );
    ct2->setField("dmLevel", NULL, "___shs__dummy__");
    if (ct->setField("shsWriter", "Configuration of the dataMemory writer sub-component which is used to dump the SHS spectrum.",
                      ct2, NO_ARRAY) == -1) {
       rA = 1; // if subtype not yet found, request , re-register in the next iteration
    }
  }

  // if the inherited config type was found, we register our configuration variables
  SMILECOMPONENT_IFNOTREGAGAIN( {} // <- this is only to avoid compiler warnings...
    // name append has a special role: it is defined in cDataProcessor, and can be overwritten here:
	  // if you set description to NULL, the existing description will be used, thus the following call can
  	// be used to update the default value:
    //ct->setField("nameAppend",NULL,"processed");

    // add custom config here...
    ct->setField("inputFieldSearch",NULL,"Mag_logScale");
    ct->setField("nHarmonics","Number of harmonics to consider for subharmonic sampling (feasible values: 5-15)",15);
    ct->setField("compressionFactor","The factor for successive compression of sub-harmonics",0.85);
    ct->setField("voicingCutoff",NULL,0.70);
    ct->setField("octaveCorrection","1 = enable low-level octave correction tuned for the SHS algorithm (will affect F0C1, voicingC1 and F0raw output fields) [EXPERIMENTAL! MAY BREAK CORRECT PITCH DETECTION!]",0);
    ct->setField("greedyPeakAlgo","1 = use new algorithm to return all maximum score candidates regardless of their order. The old algorithm added new candidates only if they were higher scored as the first one. Enabling this seems to require different viterbi parameters for smoothing though, so use with caution! Default behaviour is 'off' so we remain backwards compatibility.",0);
    ct->setField("shsSpectrumOutput", "If set to 1, then the sub-harmonic summation spectra frames will be written to the level specified by shsWriter.dmLevel.", 0);
    ct->setField("lfCut", "> 0 = remove low frequency information up to given frequency from input spectrum by zeroing all bins below.", 0);
  )

  // The configType gets automatically registered with the config manger by the SMILECOMPONENT_IFNOTREGAGAIN macro
  
  // we now create out sComponentInfo, including name, description, success status, etc. and return that
  SMILECOMPONENT_MAKEINFO(cPitchShs);
}

SMILECOMPONENT_CREATE(cPitchShs)

//-----

cPitchShs::cPitchShs(const char *_name) :
  cPitchBase(_name),
    SS(NULL), Fmap(NULL), shsWriter_(NULL), shsVector_(NULL)
{
  char *tmp = myvprint("%s.shsWriter", getInstName());
  shsWriter_ = (cDataWriter *)(cDataWriter::create(tmp));
  if (shsWriter_ == NULL)
    COMP_ERR("Error creating dataWriter '%s'",tmp);
  if (tmp != NULL)
    free(tmp);
}

void cPitchShs::fetchConfig()
{
  cPitchBase::fetchConfig();

  // fetch custom config here...
  nHarmonics = getInt("nHarmonics");
  SMILE_IDBG(2,"nHarmonics = %i\n",nHarmonics);

  compressionFactor = (FLOAT_DMEM)getDouble("compressionFactor");
  SMILE_IDBG(2,"compressionFactor = %f\n",compressionFactor);

  greedyPeakAlgo = getInt("greedyPeakAlgo");

  shsSpectrumOutput = getInt("shsSpectrumOutput");
  lfCut_ = getDouble("lfCut");
}

void cPitchShs::mySetEnvironment()
{
  cPitchBase::mySetEnvironment();
  if (shsSpectrumOutput != 0) {
    shsWriter_->setComponentEnvironment(getCompMan(), -1, this);
  }
}

int cPitchShs::myRegisterInstance(int *runMe)
{
  int ret = cPitchBase::myRegisterInstance(runMe);
  if (shsSpectrumOutput != 0) {
    ret *= shsWriter_->registerInstance();
  }
  return ret;
}

int cPitchShs::myConfigureInstance()
{
  int ret = cPitchBase::myConfigureInstance();
  if (ret != 0 && shsSpectrumOutput != 0) {
    const sDmLevelConfig * c = reader_->getConfig();
    sDmLevelConfig c2(*c);
    shsWriter_->setConfig(c2, 0);
    if (!(shsWriter_->configureInstance())) return 0;
  }
  return ret;
}

int cPitchShs::myFinaliseInstance()
{
  int ret = cPitchBase::myFinaliseInstance();
  if (ret != 0 && shsSpectrumOutput != 0) {
    if (!(shsWriter_->finaliseInstance())) return 0;
  }
  return ret;
}

int cPitchShs::cloneInputFieldInfoShs(int sourceFidx, int targetFidx, int force)
{
  const FrameMetaInfo * fmeta = reader_->getFrameMetaInfo();
  if ((fmeta != NULL) && (sourceFidx < fmeta->N)) {
    const FrameMetaInfo * fmetaW = shsWriter_->getFrameMetaInfo();
    int isset = 0;
    if (fmetaW != NULL) {
      if ((fmetaW->N > 0) && (fmetaW->field[fmetaW->N-1].infoSet)) isset = 1;
      if (!isset || force) {
        if (fmeta->field[sourceFidx].infoSize > 0) { // TODO: check why we had infoSize==0 here!! (valgrind leak check revealed this...)
          void * _info = malloc(fmeta->field[sourceFidx].infoSize);
          memcpy(_info, fmeta->field[sourceFidx].info, fmeta->field[sourceFidx].infoSize);
          shsWriter_->setFieldInfo(targetFidx, fmeta->field[sourceFidx].dataType, _info , fmeta->field[sourceFidx].infoSize );
        }
      }
      return 1;
    }
  }
  return 0;
}

int cPitchShs::setupNewNames(long nEl)
{
  int n = cPitchBase::setupNewNames(nEl);
  // set up custom names here:

  cVectorMeta *mdata = reader_->getLevelMetaDataPtr();
  FLOAT_DMEM _fmint, _fmaxt, _fmin;
  if (mdata != NULL) {
    _fmin = mdata->fData[0];
    //_fmax = mdata->fData[1];
    nOctaves = mdata->fData[2];
    nPointsPerOctave = mdata->fData[3];
    _fmint = mdata->fData[4];
    _fmaxt = mdata->fData[5];
    if (nOctaves == 0.0) {
      SMILE_IERR(1,"cannot read valid 'nOctaves' from input level meta data, please check if the input is a log(2) scale spectrum from a cSpecScale component!");
      COMP_ERR("aborting!");
    }
  }

  // check for octave scaling:
  base = exp( log((double)_fmin)/(double)_fmint );
  if (fabs(base-2.0) < 0.00001) {
   // oct scale ok
    base = 2.0;
  } else {
   // warn: not oct scale, adjust base internally... untested!
    SMILE_IWRN(1,"log base is not 2.0 (no octave scale spectrum)! Untested behaviour! (base = %f, _fmin %f, _fmint %f)",base,_fmin,_fmint);
  }

  Fmint = _fmint;
  Fstept = (_fmaxt-_fmint)/(FLOAT_DMEM)(nInput_-1);

/*  // build frequency mapping for log spectral axis: (obsolete!?)
  Fmap = (FLOAT_DMEM*)malloc(sizeof(FLOAT_DMEM)*nInput);
  long i; 
  for (i=0; i<nInput; i++) {
    Fmap[i] = exp(f*log(base)); // we assume octave scaling here!!
    f += fstep;
  }*/

  // allocate array for sum spectrum
  SS = (FLOAT_DMEM*)malloc(sizeof(FLOAT_DMEM)*nInput_);

  if (shsSpectrumOutput != 0) {
    int _N = reader_->getLevelNf();
    int i;
    for (i=0; i<_N; i++) {
      int __N=0;
      int arrNameOffset=0;
      const char *tmp = reader_->getFieldName(i, &__N, &arrNameOffset);
      shsWriter_->addField( tmp, __N, arrNameOffset );
      cloneInputFieldInfo(i, -1, 0); // last parameter = 0 => no overwrite of info data
    }
  }
  return n;
}

int cPitchShs::pitchDetect(FLOAT_DMEM * _inData, long _N, double _fsSec, double _baseT, FLOAT_DMEM *_f0cand, FLOAT_DMEM *_candVoice, FLOAT_DMEM *_candScore, long _nCandidates)
{
  int nCand = 0;
  long i,j;
  if (nOctaves == 0.0) return -1;
  

	/* remove lower frequencies */
  if (lfCut_ > 0.0) {
    int bin = (int)((ceil(log(lfCut_)/log(base)) - Fmint)/Fstept);
    SMILE_IMSG(2, "lfCut: <= bin %i from %i", bin, _N);
    for (i = 0; i <= bin; i++) {
      _inData[i] = 0.0;
    }
  }

  for (j=0; j < _N; j++) {
    SS[j] = _inData[j];
  }

  /* subharmonic summation; shift spectra by octaves and add */



  FLOAT_DMEM _scale = compressionFactor;
  
  for (i=2; i < nHarmonics+1; i++) {
    long shift = (long)floor ((double)nPointsPerOctave * smileMath_log2(i));
    for (j=shift; j < _N; j++) {
      SS[j-shift] += _inData[j] * _scale;
    }
    _scale *= compressionFactor;
  }
  for (j=0; j < _N; j++) {
    SS[j] /= (FLOAT_DMEM)nHarmonics;  // Is this needed?
    if (SS[j] < 0) SS[j] = 0.0;
  }
  // TODO : support output of SHS spectrum here for vis and debug
  if (shsSpectrumOutput != 0) {
    if (shsVector_ == NULL) {
      shsVector_ = new cVector(_N, DMEM_FLOAT);
    }
    memcpy(shsVector_->dataF, SS, _N * sizeof(FLOAT_DMEM));
    // TODO: properly set timestamps
    shsWriter_->setNextFrame(shsVector_);
  }

  // peak candidate picking & computation of SS vector mean
  _candScore[0] = 0.0;
  double ssMean = (double)SS[0];
  for (i=1; i<_N-1; i++) {
    if (greedyPeakAlgo) { // use new (correct?) max. score peak detector


    if ( (SS[i-1] < SS[i]) && (SS[i] > SS[i+1]) ) { // <- peak detection
        //    && ((SS[i] > _candScore[0])||(_candScore[0]==0.0)) ) { // is max. peak or first peak?

      // add candidate at first free spot or behind another higher scored one...
      for (j=0; j<_nCandidates; j++) {
        if (_candScore[j]==0.0 || _candScore[j]<SS[i]) {
          // move remaining candidates downwards..
          int jj;
          for (jj=_nCandidates-1; jj>j; jj--) {
            _candScore[jj] = _candScore[jj-1];
            _f0cand[jj] = _f0cand[jj-1];
          }
          // add this one...
          _f0cand[j] = (FLOAT_DMEM)i;
          _candScore[j] = SS[i];
          if (nCand<_nCandidates) nCand++;
          break; // leave the for loop after adding candidate to array
        }
      }

    }
    } else {

      if ( (SS[i-1] < SS[i]) && (SS[i] > SS[i+1])
              && ((SS[i] > _candScore[0])||(_candScore[0]==0.0)) ) { // is max. peak or first peak?


  // TODO:!! this algorithm might only add one candidate, if the first one added is the maximum score candidate. This will degarde performance of following viterbi smoothing!
  // CLEAN SOLUTION: find all peaks, then sort by score, and output to "nCandidates"
  // old algo:
        // shift candScores and f0cand (=indicies)
        for (j=_nCandidates-1; j>0; j--) {
          _candScore[j] = _candScore[j-1];
          _f0cand[j] = _f0cand[j-1];
        }
        _f0cand[0] = (FLOAT_DMEM)i;
        _candScore[0] = SS[i];
        if (nCand<_nCandidates) nCand++;
      }

    }
    ssMean += (double)SS[i];
  }
  ssMean = (ssMean+(double)SS[i])/(double)_N;

  // convert peak candidate frequencies and compute voicing prob.
  for (i=0; i<nCand; i++) {
    long j = (long)_f0cand[i];
    // parabolic peak interpolation:
    FLOAT_DMEM f1 = _f0cand[i]*Fstept + Fmint;
    FLOAT_DMEM f2 = (_f0cand[i]+(FLOAT_DMEM)1.0)*Fstept + Fmint;
    FLOAT_DMEM f0 = (_f0cand[i]-(FLOAT_DMEM)1.0)*Fstept + Fmint;
    double sc=0;
    double fx = smileMath_quadFrom3pts((double)f0, (double)SS[j-1], (double)f1, (double)SS[j], (double)f2, (double)SS[j+1], &sc, NULL);
    // convert log(2) frequency scale to lin frequency scale (Hz):
    _f0cand[i] = (FLOAT_DMEM)exp(fx*log(base));
    _candScore[i] = (FLOAT_DMEM)sc;
    if ((sc > 0.0)&&(sc>ssMean)) {
      _candVoice[i] = (FLOAT_DMEM)( 1.0 - ssMean/sc );
    } else {
      _candVoice[i] = 0.0;
    }
  }

  // octave correction of first candidate:
  if (octaveCorrection) {
    /*
     algo: prefer lower candidate, if voicing prob of lower candidate approx. voicing prob of first candidate (or > voicing cutoff)
     and if score of lower candidate > ( 1/((nHarmonics-1)*compressionFactor) )*score of cand[0]
     */
    for (i=0; i<nCand; i++) {
      if ( (_f0cand[i] < _f0cand[0])&&(_f0cand[i] > 0) && ((_candVoice[i] > voicingCutoff)||(_candVoice[i]>=0.9*voicingCutoff)) && (_candScore[i] > ((1.0/(FLOAT_DMEM)(nHarmonics-1)*compressionFactor))*_candScore[0]) ) {
        // then swap:
        FLOAT_DMEM tmp;
        tmp = _f0cand[0];
        _f0cand[0] = _f0cand[i]; 
        _f0cand[i] = tmp;
        tmp = _candVoice[0];
        _candVoice[0] = _candVoice[i]; 
        _candVoice[i] = tmp;
        tmp = _candScore[0];
        _candScore[0] = _candScore[i]; 
        _candScore[i] = tmp;
      }
    }
  }

  // return actual number of candidates on success (-1 on failure...)
  return nCand;
}

int cPitchShs::addCustomOutputs(FLOAT_DMEM *dstCur, long NdstLeft)
{
  // to be implemented by child class

  // return the number of custom outputs that were added..
  return 0;
}


cPitchShs::~cPitchShs()
{
  if (SS != NULL) free(SS);
  if (Fmap != NULL) free(Fmap);
  if (shsVector_ != NULL) delete(shsVector_);
  if (shsWriter_ != NULL) delete(shsWriter_);
}

