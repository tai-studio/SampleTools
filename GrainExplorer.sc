GrainExplorer {
	var <>path, <dirPath, <synthDef, <numChans, <>author;
	var <window, <sliders;

	var controls, usefulControls, numControls = 0;
	var sRate = 44100, format = "int24", headerFormat = "AIFF";
	var <font;


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
		font = Font("Helvetica",10);
		this.synthDef_(argSynthDef);
		author = argAuthor ? "LFSaw.de";
	}

	filePath {
		^(dirPath +/+ "%-%".format(
			synthDef.name, Date.getDate.asSortableString
		));
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

	getSliderValues {|noOut = false|
		var envir;

		envir = ();
		usefulControls.do {|controlName, i|
			var ctlname = controlName.name.asString;
			var sliderEntry = sliders[controlName.name];
			if ( not(noOut and: {ctlname == "out"})) {
				if(ctlname[1] == $_ and: { "ti".includes(ctlname[0]) }) {
					ctlname = ctlname[2..];
				};

				if (sliderEntry.isArray) {
					envir.put(controlName.name, sliderEntry.collect(_.value));
				} {
					envir.put(controlName.name, sliderEntry.value);
				}
			};
		};
		^envir.use {
			synthDef.desc.msgFunc.valueEnvir
		};
	}

	pr_assembleSynthDefString {
		^(
			"%.metadata_(( 'specs': \n%\n)).add".format(
				synthDef.asCompileString,
				sliders.collect(_.controlSpec).asCompileString
			)
		)
	}

	renderAndWrite {
		var filePath = this.filePath();
		var params = this.getSliderValues;
		var paramsEvent = Event.newFrom(params);
		var dur = paramsEvent[\dur] ?? {paramsEvent[\sustain] ?? {1}};
		dur = dur + 0.1; // add safety margin;


		File.exists(dirPath).not.if{
			dirPath.mkdir;
		};

		// write definition
		this.pr_writeDefFile(
			"%.%".format(filePath, "scd")
		);

		// render sound
		Routine{
			this.pr_renderSound(
				"%-%-%.%".format(filePath, format, sRate, headerFormat.toLower).postln,
				params: params,
				numChans: numChans,
				dur: dur.postln,
				sRate: sRate,
				headerFormat: headerFormat,
				format: format
			);
		}.play
	}

	pr_renderSound {|filename, numChans = 2, dur = 1, params, sRate, headerFormat, format|
		var score;
		var args;
		var name;

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
		score.score.printAll;

		score.recordNRT(
			// oscFilePath: nil,
			outputFilePath: filename.postln,
			// inputFilePath: nil,
			sampleRate: sRate.postln,
			headerFormat: headerFormat,
			sampleFormat: format.postln,
			options: ServerOptions().numOutputBusChannels_(numChans).postln,
			duration: dur.postln
		);
	}

	play {
		// {
		synthDef.play(args: this.getSliderValues);
		// }.defer
	}

	pr_syncMetaData {
		synthDef.metadata = ();
		synthDef.metadata.specs = sliders.collect(_.controlSpec);
		// write metadata to desc
		synthDef.add;
	}

	pr_writeDefFile {|filePath|
		var defFile = File(filePath,"w");

		defFile.write("// %\n//\n".format(synthDef.name));
		defFile.write("// % (%)\n//\n".format(author, Date.getDate));
		defFile.write(
			this.getSliderValues.clump(2).collect{|e|
				"//   %: %\n".format(*e)
			}.inject("", {|c, n| c ++ n})
		);
		defFile.write("\n\n");
		defFile.write(
			this.pr_assembleSynthDefString
		);
		defFile.write("\n\n\n/*\nSynth.grain(%, %);\n*/".format(
			synthDef.name.asCompileString, this.getSliderValues.asCompileString
		));
		defFile.close;
		filePath.postln;
	}

	edit {|elHeight = 20|
		this.makeWindow(Rect(0, 0, 620, Window.screenBounds.height - 20), elHeight)
	}
	makeWindow {|bounds, elHeight = 20|
		var playButton, saveButton, codeView;
		var pad = 10;
		var width;
		var buttonWidth = 70;

		sliders = ();

		// make the window
		window = Window.new(synthDef.desc.name, bounds);
		window.view.decorator = FlowLayout(window.view.bounds);
		window.view.background = Color.grey(0.5, 1.0);
		width = window.view.decorator.indentedRemaining.width;

		window.view.keyDownAction_{|view... b|
			(b[1] == $ ).if{
				this.play
			};
			(b.last == 16777220 and: {b[1] == 524288 or: {b[1] == 131072}}).if{
				this.play
			}
		};

		// add a button to trigger the sound.
		playButton = Button.new(window, buttonWidth @ elHeight);
		playButton.states = [
			["trig", Color.black, Color.gray]
		];
		playButton.action = { |view|
			this.play
		};

		saveButton = Button.new(window, buttonWidth @ elHeight);
		saveButton.states = [
			["save", Color.black, Color.gray]
		];
		saveButton.action = { |view|
			// "save".postln;
			this.renderAndWrite
		};

		// sRate
		PopUpMenu(window, buttonWidth @ elHeight)
		.items_(
			[44100, 48000, 96000]
		)
		.action_{|me|
			sRate = me.item;
		};

		// format
		PopUpMenu(window, buttonWidth @ elHeight)
		.items_(
			["int24", "float24", "float32"]
		)
		.action_{|me|
			format = me.item;
		};
		// headerFormat
		PopUpMenu(window, buttonWidth @ elHeight)
		.items_(
			["AIFF", "WAV"]
		)
		.action_{|me|
			headerFormat = me.item;
		};

		// reopen window
		Button.new(window, buttonWidth @ elHeight)
		.states_([
			["win", Color.black, Color.gray]
		])
		.action_({ |view|
			var origBounds = window.bounds;
			var origVals = sliders.collect(_.value);
			this.pr_syncMetaData;
			window.close;
			this.makeWindow(origBounds);
			sliders.keysValuesDo{|key, slider|
				origVals[key].notNil.if{
					slider.value = origVals[key]
				}
			}
		});


		// create controls for all parameters
		usefulControls.do {|controlName|
			var ctlname, ctlname2, capname, spec, controlIndex, slider;
			ctlname = controlName.name;
			capname = ctlname.asString;
			// capname[0] = capname[0].toUpper;
			window.view.decorator.nextLine;
			ctlname = ctlname.asSymbol;
			if((spec = synthDef.desc.metadata.tryPerform(\at, \specs).tryPerform(\at, ctlname)).notNil) {
				spec = spec.asSpec
			} {
				spec = ctlname.asSpec;
			};

			if (spec.isKindOf(ControlSpec)) {
				slider = EZSlider(
					window,
					width @ elHeight,
					capname,
					spec,
					controlName.defaultValue,
					unitWidth:30,
					numberWidth:60,
					layout:\horz
				)
			} {
				spec = ControlSpec(0, 1);
				if (controlName.defaultValue.isNumber) {
					slider = EZSlider(
						window,
						width @ elHeight,
						capname,
						spec,
						controlName.defaultValue,
						unitWidth:30,
						numberWidth:60,
						layout:\horz
					)
				} {
					slider = Array(controlName.defaultValue.size);
					controlName.defaultValue.do {|value, i|
						slider.add(EZNumber(
							window,
							96 @ elHeight,
							"%[%]".format(capname, i),
							spec,
							value,
							unitWidth:30,
							numberWidth:60,
							// layout:\horz
						))
					}
				}
			};
			if (slider.isKindOf(Array)) {
				slider.do{|sl|
					sl.setColors(
						stringBackground:	Color(alpha: 0),
						stringColor: 		Color.white,
						sliderBackground:	Color.grey(0.2),
						numBackground: 		Color.grey(0.2),
						numStringColor: 	Color.white,
						numNormalColor: 	Color.yellow,
						background: 		Color(alpha: 0)
					);
					sl.font_(font);
				}
			} {
				slider.setColors(
					stringBackground:	Color(alpha: 0),
					stringColor: 		Color.white,
					sliderBackground:	Color.grey(0.2),
					numBackground: 		Color.grey(0.2),
					numStringColor: 	Color.white,
					numNormalColor: 	Color.yellow,
					background: 		Color(alpha: 0)
				);
				slider.font_(font);
			};

			sliders.put(ctlname, slider)
		};

		window.view.decorator.nextLine;

		codeView = TextView(window,
			width @ (window.view.decorator.indentedRemaining.height)
		)
		.resize_(5)
		.enterInterpretsSelection_(false)
		.font_(Font(Font.defaultMonoFace, 12))
		.keyDownAction_{|view ... b|
			(b.last == 16777220 and: {b[1] == 524288 or: {b[1] == 131072}}).if{
				this.synthDef_(view.string.interpret);
				// evaluated by global keydownAction
				//this.play;
			}
		}
		.tabWidth_(21)
		.string = synthDef.asCompileString;

		// window.onClose{window = nil};
		window.front; // make window visible and front window.
	}
}