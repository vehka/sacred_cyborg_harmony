Engine_TheMachine : CroneEngine {
	classvar luaOscPort = 10111;

  var pitchFinderSynth, infoBus, voiceInBus, backgroundBus, quantizedVoice, harmonyVoices, pitchHandler, leadBus, choirBus, endOfChainSynth;
  var inL, inR, backL, backR, backPan;
  var fileBus, sourceGroup, sampleBuf, samplePlayer, micLevel = 1, fileLevel = 0;
  var lowFreq = 82, highFreq = 1046;
  
	*new { arg context, doneCallback;
    	  
		^super.new(context, doneCallback);
	}
	  

  alloc {
  	var luaOscAddr = NetAddr("localhost", luaOscPort);

	  harmonyVoices = Dictionary.new;
	  
	  pitchHandler = OSCdef.new(\pitchHandler, { |msg, time|
			var pitch = msg[3].asFloat;
            var amp = msg[4].asFloat;
			luaOscAddr.sendMsg("/measuredPitch", pitch, amp);
		}, '/pitch');
		
		this.addCommand("setMix", "fffff", { |msg|
		  inL = msg[1].asFloat;
		  inR = msg[2].asFloat;
		  backL = msg[3].asFloat;
		  backR = msg[4].asFloat;
		  backPan = msg[5].asFloat;
		
		  if (pitchFinderSynth != nil, {
		    pitchFinderSynth.set(
		      \inL, inL,
		      \inR, inR,
		      \backL, backL,
		      \backR, backR,
		      \backgroundPan, backPan);
		  });
		});
		
		// Levels of the two sources feeding pitch tracking and the voices.
		this.addCommand("setSource", "ff", { |msg|
		  micLevel = msg[1].asFloat;
		  fileLevel = msg[2].asFloat;
		  if (pitchFinderSynth != nil, {
		    pitchFinderSynth.set(\micLevel, micLevel, \fileLevel, fileLevel);
		  });
		});
		
		// Load a sound file to use in place of the mic. A mono file is read
		// into both channels, so both input styles work with it.
		this.addCommand("sampleLoad", "si", { |msg|
		  var path = msg[1].asString;
		  var channels = if (msg[2].asInteger > 1, { [0, 1] }, { [0, 0] });
		  Buffer.readChannel(context.server, path, channels: channels, action: { |buf|
		    var old = sampleBuf;
		    if (samplePlayer != nil, { samplePlayer.set(\gate, 0); samplePlayer = nil; });
		    sampleBuf = buf;
		    // Let the old player fade out before its buffer goes away.
		    if (old != nil, { SystemClock.sched(0.1, { old.free; nil }); });
		  });
		});
		
		// Play the sample once from the start, fading out any earlier play.
		this.addCommand("samplePlay", "", { |msg|
		  if (sampleBuf != nil, {
		    var player;
		    if (samplePlayer != nil, { samplePlayer.set(\gate, 0); });
		    player = Synth(\samplePlayer, [out: fileBus, buf: sampleBuf], target: sourceGroup);
		    player.onFree({ if (samplePlayer === player, { samplePlayer = nil; }); });
		    samplePlayer = player;
		  });
		});
		
		this.addCommand("sampleStop", "", { |msg|
		  if (samplePlayer != nil, { samplePlayer.set(\gate, 0); samplePlayer = nil; });
		});
		
		// Pitch.kr only reads its range when it starts, so swap the follower.
		// Before the first follower exists, just remember the range for it.
		this.addCommand("setInputRange", "ff", { |msg|
		  lowFreq = msg[1].asFloat;
		  highFreq = msg[2].asFloat;
		  if (pitchFinderSynth != nil, {
		    pitchFinderSynth.free;
		    pitchFinderSynth = Synth(\follower, [
		      infoBus: infoBus,
		      voiceInBus: voiceInBus,
		      backgroundBus: backgroundBus,
		      fileBus: fileBus,
		      micLevel: micLevel,
		      fileLevel: fileLevel,
		      inL: inL,
		      inR: inR,
		      backL: backL,
		      backR: backR,
		      backgroundPan: backPan,
		      minFreq: lowFreq,
		      maxFreq: highFreq], addAction: \addAfter, target: sourceGroup);
		  });
		});
		
		this.addCommand("acceptQuantizedPitch", "ffffff", { |msg|
			var pitch = msg[1].asFloat;
			var pull = msg[2].asFloat;
			var amp = msg[3].asFloat;
			var formantRatio = msg[4].asFloat;
			var acquisition = msg[5].asFloat;
			var pan = msg[6].asFloat;
			if(quantizedVoice != nil, {
  			quantizedVoice.set(\targetHz, pitch, \pull, pull, \amp, amp, \formantRatio, formantRatio, \acquisition, acquisition, \pan, pan);
  		});
		});
		
		this.addCommand("noteOn", "fffffffi", { |msg|
			var pitch = msg[1].asFloat;
			var velocity = msg[2].asFloat;
			var delay = msg[3].asFloat;
			var vibratoAmount = msg[4].asFloat;
			var vibratoSpeed = msg[5].asFloat;
			var formantRatio = msg[6].asFloat;
			var pan = msg[7].asFloat;
			var voice = msg[8].asInteger;
			if(harmonyVoices.includesKey(voice), {
  			harmonyVoices[voice].set(\targetHz, pitch);
  			harmonyVoices[voice].set(\amp, velocity);
  		}, {
  		  harmonyVoices[voice] = Synth(\grainVoice, [
  		    out: 0, 
  		    infoBus: infoBus,
  		    voiceIn: voiceInBus,
  		    targetHz: pitch, 
  		    amp: velocity, 
  		    timeDispersion: 0.01,
  		    delay: delay,
  		    vibratoAmount: vibratoAmount,
  		    vibratoSpeed: vibratoSpeed,
  		    formantRatio: formantRatio], addAction: \addAfter, target: pitchFinderSynth);
  		});
		});
		
		this.addCommand("noteOff", "i", { |msg|
		  var voice = msg[1].asInteger;
		  if(harmonyVoices.includesKey(voice), {
		    harmonyVoices[voice].set(\gate, 0);
		    harmonyVoices[voice] = nil;
		  });
		});
  
    Routine.new({
      infoBus = Bus.control(numChannels: 2);
      leadBus = Bus.audio(numChannels: 2);
      choirBus = Bus.audio(numChannels: 2);
      backgroundBus = Bus.audio(numChannels:2);
      voiceInBus = Bus.audio(numChannels: 1);
      fileBus = Bus.audio(numChannels: 2);
      // Sample players live here, ahead of the follower that reads them.
      sourceGroup = Group.new(context.server);
      
      SynthDef(\samplePlayer, { |out, buf, gate=1|
        var snd = PlayBuf.ar(2, buf, BufRateScale.kr(buf), doneAction: Done.freeSelf);
        var env = EnvGen.kr(Env.asr(0.005, 1, 0.02), gate, doneAction: Done.freeSelf);
        Out.ar(out, snd * env);
      }).add;
      
      SynthDef(\endOfChain, { 
      
        Out.ar(0, (In.ar(backgroundBus, numChannels: 2) + In.ar(leadBus, numChannels: 2) + In.ar(choirBus, numChannels: 2)).softclip);
      
      }).add;
      
      SynthDef(\follower, { |infoBus, voiceInBus, backgroundBus, fileBus, inL, inR, backL, backR, backgroundPan, minFreq=82, maxFreq=1046, micLevel=1, fileLevel=0|
        var in = (SoundIn.ar([0, 1]) * micLevel.lag(0.05)) + (In.ar(fileBus, 2) * fileLevel.lag(0.05));
        var snd = Mix.ar([inL, inR]*in);
        var background = Mix.ar([backL, backR]*in);
        var reference = LocalIn.kr(1);
        var info = Pitch.kr(snd, minFreq: minFreq, maxFreq: maxFreq);
        var midi = info[0].cpsmidi;
        var trigger = info[1]*((midi - reference).abs > 0.2) + Impulse.kr(0.5);
        LocalOut.kr([Latch.kr(midi, trigger)]);
        SendReply.kr(trigger, '/pitch', [info[0], Mix.kr(Amplitude.kr(in))]);
        Out.kr(infoBus, info);
        Out.ar(voiceInBus, snd);
        Out.ar(backgroundBus, Pan2.ar(background, backgroundPan));
      }).add;
    
      SynthDef(\grainVoice, { |out, voiceIn, infoBus, targetHz, amp=1, delay=0, formantRatio=1, gate=1, vibratoAmount = 0, 
                               vibratoSpeed = 3, pull = 1, timeDispersion = 0.01, acquisition = 0.1, pan = 0|
        var info = DelayN.kr(In.kr(infoBus, 2), delay+0.01, delay);
        var env = Env.asr(0.2);
        var envUgen = EnvGen.kr(env, gate, doneAction: Done.freeSelf);        
        var snd = DelayN.ar(In.ar(voiceIn, 1), delay+0.01, delay);
        var adjustedTargetHz = (targetHz.cpsmidi.lag(0.05) + (envUgen * Amplitude.kr(snd)*vibratoAmount*SinOsc.kr(vibratoSpeed))).midicps;
        var ratio = (pull*info[1].lag(acquisition).if(adjustedTargetHz/info[0], 1)) + (1 - pull);
        var shiftedSound, pannedSound;
        
        ratio = Sanitize.kr(ratio, 1);
        shiftedSound = amp.lag(0.1)*envUgen*PitchShiftPA.ar(snd, freq: info[0], pitchRatio: ratio, formantRatio: formantRatio, timeDispersion: timeDispersion);
        pannedSound = Pan2.ar(shiftedSound, pan);
        Out.ar(out, pannedSound); 
      }).add;    
      
      Server.default.sync;
      // This runs the whole time.
      pitchFinderSynth = Synth(\follower, [infoBus: infoBus, voiceInBus: voiceInBus, backgroundBus: backgroundBus, fileBus: fileBus, micLevel: micLevel, fileLevel: fileLevel, inL: inL ? 0.5, inR: inR ? 0.5, backL: backL ? 0, backR: backR ? 0, backgroundPan: backPan ? 0, minFreq: lowFreq, maxFreq: highFreq], addAction: \addAfter, target: sourceGroup);
      quantizedVoice = Synth(\grainVoice, [out: leadBus, voiceIn: voiceInBus, infoBus: infoBus, targetHz: 180, timeDispersion: 0.01], addAction: \addAfter, target: pitchFinderSynth);
      endOfChainSynth = Synth(\endOfChain, addAction: \addToTail);
    }).play;
  }
  
  free {
    quantizedVoice.free;
    harmonyVoices.do { |v|
      v.free
    };    
    pitchFinderSynth.free;
    infoBus.free;
    pitchHandler.free;
    endOfChainSynth.free;
    choirBus.free;
    leadBus.free;
    backgroundBus.free;
    voiceInBus.free;
    sourceGroup.free;
    fileBus.free;
    sampleBuf.free;
  }
}