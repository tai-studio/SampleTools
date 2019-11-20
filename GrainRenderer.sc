GrainRenderer {
	var <path, <dirPath, <synthDef, <numChans, <>author;
	var <>fileIdentifier; // if nil, unique date string used.
	var <>comment;
	var <controlVals;
	var <buffers;
	var controls, usefulControls, numControls = 0;
	var <>sRate = 44100, <>format = "int24", <>headerFormat = "AIFF", <>blockSize = 128;

	*new {|synthDef, numChans = 2, path, author|
		^super.new.init(synthDef, numChans, path, author)
	}

	init {|argSynthDef, argNumChans, argPath, argAuthor|
		argPath.isNil.if({
			// write to default location
			path = "~/Desktop".standardizePath;
		}, {
			path = argPath
		});
		numChans = argNumChans;
		buffers = ();
		this.synthDef_(argSynthDef);
		this.initControlVals;
		author = argAuthor ? "LFSaw.de";
	}
	initControlVals {
		controlVals = ();

		usefulControls.do {|controlName|
			var ctlname, controlVal;
			ctlname = controlName.name.asSymbol;

			controlVal = controlName.defaultValue;

			controlVals.put(ctlname, controlVal)
		}
	}

	setBufferData {|bufNum, data| // non-interleaved
		buffers[bufNum] = data;
	}
	filePaths {|subdirs|
		var str, identifier, dateHash;

		dateHash = Date.getDate.asSortableString;

		fileIdentifier.notNil.if({
			identifier = fileIdentifier;
		}, {
			identifier = dateHash;
		});

		str = (dirPath +/+ "%" +/+ "%-%".format(
			synthDef.name, identifier
		));

		^subdirs.collect{|dir|
			str.format(dir)
		}
	}

	path_{|val|
		path = val;
		dirPath = path +/+ synthDef.name;
	}

	synthDef_ {|val|
		var newNumControls;
		synthDef = val;
		synthDef.add;

		dirPath = path +/+ synthDef.name;

		controls = synthDef.desc.controls;
		usefulControls = controls.select {|controlName, i|
			var ctlname;
			ctlname = controlName.name.asString;
			(ctlname != "?") && (synthDef.desc.msgFuncKeepGate or: { ctlname != "gate" })
		};
		newNumControls = usefulControls.collect(_.defaultValue).flatten.size;
		(newNumControls > numControls).if{
			"number of controls updated. please reopen GUI"
		};
		numControls = newNumControls;
	}


	pr_assembleSynthDefString {
		^(
			"%.add;".format(
				synthDef.asCompileString
			)
		)
	}

	renderAndWrite {|writeDef = true|
		var filePaths = this.filePaths(["defs", "samples"]);
		var params = this.getValues;
		var paramsEvent = Event.newFrom(params);
		var dur = paramsEvent[\dur] ?? {paramsEvent[\sustain] ?? {1}};
		var subDirs;
		// dur = dur + 0.1; // add safety margin;

		File.exists(dirPath).not.if{
			dirPath.mkdir;
		};
		subDirs = writeDef.if({
			["defs", "samples"]
		}, {
			["samples"]
		});
		subDirs.do{|subDirName|
			File.exists(dirPath +/+ subDirName).not.if{
				(dirPath +/+ subDirName).mkdir;
			};
		};

		// write definition
		writeDef.if{
			this.pr_writeDefFile(
				"%.%".format(filePaths[0], "scd"),
				comment
			);
		};

		// render sound
		Routine{
			this.pr_renderSound(
				"%-%-%.%".format(filePaths[1], format, sRate, headerFormat.toLower),
				params: params,
				numChans: numChans,
				dur: dur,
				sRate: sRate,
				headerFormat: headerFormat,
				format: format,
				blockSize: blockSize
			);
		}.play
	}


	render {|filename|
		var params = this.getValues;
		var paramsEvent = Event.newFrom(params);
		var dur = paramsEvent[\dur] ?? {paramsEvent[\sustain] ?? {1}};
		var subDirs;

		// render sound
		Routine{
			this.pr_renderSound(
				filename,
				params: params,
				numChans: numChans,
				dur: dur,
				sRate: sRate,
				headerFormat: headerFormat,
				format: format,
				blockSize: blockSize
			);
		}.play
	}


	pr_renderSound {|filename, numChans = 2, dur = 1, params, sRate, headerFormat, format, blockSize = 128|
		var score;
		var args;
		var name;
		// sc is off by one block in NRT
		var renderDur = dur - (blockSize / sRate);

		name = synthDef.name;

		args = [
			\instrument, name,
			\amp, 1,
			\dur, Pseq([dur], 1),
			\legato, 1
		] ++ params;

		score = Pbind(*args).asScore(dur);
		// add synthdef after g_new
		score.score = score.score.insert(1,
			[ 0, [\d_recv, synthDef.asBytes ] ]
		);
		buffers.keysValuesDo{|bufNum, data|
			var numFrames, numChans;

			# numChans, numFrames = data.shape;
			numFrames.isNil.if{
				numFrames = numChans;
				numChans = 1;
			};
			(numChans > 1).if{
				// interleave data, flatten array
				data = data.flop.flat;
			};

			score.score = score.score.insert( 1, // before synth
				[0, [ \b_alloc, bufNum, numFrames, numChans, nil] ]
			);
			score.score = score.score.insert( 2, // after alloc
				[0, [ \b_setn, bufNum, 0, numFrames * numChans] ++ data ]
			);
		};
		score.score.printAll;

		score.recordNRT(
			oscFilePath: "%.osc".format(filename),
			outputFilePath: filename,
			// inputFilePath: nil,
			sampleRate: sRate,
			headerFormat: headerFormat,
			sampleFormat: format,
			options: ServerOptions()
			.numOutputBusChannels_(numChans)
			.blockSize_(blockSize),
			duration: renderDur
		);
	}

	play {|server|
		server = server ? Server.default;
		server.bind{
			buffers.keysValuesDo{|bufNum, data|
				var numFrames, numChans;
				# numChans, numFrames = data.shape;
				numFrames.isNil.if{
					numFrames = numChans;
					numChans = 1;
				};

				(numChans > 1).if{
					// interleave data, flatten array
					data = data.flop.flat;
				};

				Buffer.alloc(server, numFrames, numChans, bufnum: bufNum).loadCollection(data);
			};
			synthDef.play(args: this.getValues);
		}
	}

	pr_writeDefFile {|filePath, comment|
		var defFile = File(filePath,"w");

		defFile.write("// %\n//\n".format(synthDef.name));
		defFile.write("// % (%)\n//\n".format(author, Date.getDate));
		defFile.write(
			this.getValues.clump(2).collect{|e|
				"//   %: %\n".format(*e)
			}.inject("", {|c, n| c ++ n})
		);
		defFile.write("\n\n");

		// comment
		comment.notNil.if{
			defFile.write("/*\n");
			defFile.write(comment);
			defFile.write("\n*/\n\n");
		};

		// buffer
		buffers.keys.notEmpty.if({
			defFile.write("s.bind{\n");
			buffers.keysValuesDo{|bufNum, data|
				var numFrames, numChans;
				# numChans, numFrames = data.shape;
				numFrames.isNil.if{
					numFrames = numChans;
					numChans = 1;
				};
				(numChans > 1).if{
					// interleave data, flatten array
					data = data.flop.flat;
				};
				defFile.write("\tBuffer.alloc(\n\ts, \n\t\t%, \n\t\t%, \n\t\tbufnum: %\n\t).loadCollection(\n\t\t%\n\t);\n".format(numFrames, numChans, bufNum, data.asCompileString));
			};
			defFile.write("};\n\n");
		});

		defFile.write(
			this.pr_assembleSynthDefString
		);
		defFile.write("\n\n\n/*\nSynth.grain(%, %);\n*/".format(
			synthDef.name.asCompileString, this.getValues.asCompileString
		));
		defFile.close;
		// filePath.postln;
	}

	getValues {|noOut = false|
		var envir;

		envir = ();
		usefulControls.do {|controlName, i|
			var ctlname = controlName.name.asString;
			var paramValEntry = controlVals[controlName.name];
			if ( not(noOut and: {ctlname == "out"})) {
				if(ctlname[1] == $_ and: { "ti".includes(ctlname[0]) }) {
					ctlname = ctlname[2..];
				};

				if (paramValEntry.isArray) {
					"array %\n".postf(controlName.name);
					envir.put(controlName.name, paramValEntry.collect(_.value));
				} {
					envir.put(controlName.name, paramValEntry.value);
				}
			};
		};
		^envir.use {
			synthDef.desc.msgFunc.valueEnvir
		};
	}

}
