/********************************************************************************
 * Copyright (c) Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: (EPL-2.0 OR Apache-2.0)
 ********************************************************************************/

package org.eclipse.transformer.cli;

import static org.eclipse.transformer.Transformer.consoleMarker;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Option.Builder;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.transformer.AppOption;
import org.eclipse.transformer.TransformException;
import org.eclipse.transformer.TransformOptions;
import org.eclipse.transformer.Transformer;
import org.eclipse.transformer.Transformer.ResultCode;
import org.eclipse.transformer.action.ActionType;
import org.eclipse.transformer.action.Changes;
import org.eclipse.transformer.util.PropertiesUtils;
import org.slf4j.Logger;

public class TransformerCLI implements TransformOptions {

	public static void main(String[] args) throws Exception {
		TransformerCLI cli = new TransformerCLI(System.out, System.out, args);
		ResultCode rc = runWith(cli);
		System.exit(rc.ordinal());
	}

	public static ResultCode runWith(TransformerCLI cli) {
		ResultCode rc = cli.run();
		return rc;
	}

	// Not in use, until option grouping is figured out.

	public static final String	INPUT_GROUP		= "input";
	public static final String	LOGGING_GROUP	= "logging";

	//

	private final Logger		logger;

	public TransformerCLI(PrintStream sysOut, PrintStream sysErr, String... args) {
		this.sysOut = sysOut;
		this.sysErr = sysErr;
		this.args = args;

		appOptions = buildOptions();

		// Need to parse args since parsed args are needed to create logger
		try {
			CommandLineParser parser = new DefaultParser();
			parsedArgs = parser.parse(getAppOptions(), getArgs());
		} catch (ParseException e) {
			throw new TransformException("Exception parsing command line arguments", e);
		}

		logger = new TransformerLoggerFactory(this).createLogger();
	}

	public Logger getLogger() {
		return logger;
	}

	private Options buildOptions() {
		Options options = new Options();
		Map<String, OptionGroup> groups = new HashMap<>();

		for (AppOption appOption : AppOption.values()) {
			Builder builder = Option.builder(appOption.getShortTag());
			builder.longOpt(appOption.getLongTag());
			builder.desc(appOption.getDescription());
			if (appOption.getHasArgs()) {
				builder.hasArg(false);
				builder.hasArgs();
			} else if (appOption.getHasArg()) {
				builder.hasArg();
			} else if (appOption.getHasArgCount()) {
				builder.numberOfArgs(appOption.getArgCount());
			} else {
				// No arguments are required for this option.
			}
			builder.required(appOption.isRequired());

			Option option = builder.build();

			String groupTag = appOption.getGroupTag();
			if (groupTag != null) {
				OptionGroup group = groups.computeIfAbsent(groupTag, k -> new OptionGroup());
				if (option.isRequired()) {
					group.setRequired(true);
				}
				group.addOption(option);
			} else {
				options.addOption(option);
			}
		}

		groups.values()
			.forEach(options::addOptionGroup);

		return options;
	}

	//

	private static final String[]	COPYRIGHT_LINES					= {
		"Copyright (c) Contributors to the Eclipse Foundation",
		"This program and the accompanying materials are made available under the",
		"terms of the Eclipse Public License 2.0 which is available at",
		"http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0",
		"which is available at https://www.apache.org/licenses/LICENSE-2.0.",
		"SPDX-License-Identifier: (EPL-2.0 OR Apache-2.0)", ""
	};

	private static final String		SHORT_VERSION_PROPERTY_NAME		= "version";

	// TODO: Usual command line usage puts SysOut and SysErr together, which
	// results in the properties writing out twice.
	//
	// See issue #297.

	private void preInitDisplay(String message) {
		PrintStream useSysOut = getSystemOut();
		// PrintStream useSysErr = getSystemErr();

		useSysOut.println(message);
		// if ( useSysErr != useSysOut ) {
		// useSysErr.println(message);
		// }
	}

	private void displayHeader() {
		if (getLogger().isDebugEnabled()) {
			for (String copyrightLine : COPYRIGHT_LINES) {
				preInitDisplay(copyrightLine);
			}
		} else {
			preInitDisplay(COPYRIGHT_LINES[0]);
		}
		Properties useBuildProperties = getBuildProperties();
		preInitDisplay(getClass().getName() + " Version [ "
			+ useBuildProperties.getProperty(SHORT_VERSION_PROPERTY_NAME, "<unavailable>") + " ]");
		preInitDisplay("");
	}

	private static final String TRANSFORMER_BUILD_PROPERTIES = "META-INF/maven/org.eclipse.transformer/org.eclipse.transformer.cli/pom.properties";

	private Properties getBuildProperties() {
		Properties useProperties;
		try {
			useProperties = loadProperties(TRANSFORMER_BUILD_PROPERTIES);
		} catch (IOException e) {
			getLogger().error("Failed to load properties [ " + TRANSFORMER_BUILD_PROPERTIES + " ]", e);
			return PropertiesUtils.createProperties();
		}
		if (useProperties.isEmpty()) {
			getLogger().error("Failed to locate properties [ " + TRANSFORMER_BUILD_PROPERTIES + " ]");
		}
		return useProperties;
	}

	private static Properties loadProperties(String resourceRef) throws IOException {
		Properties properties = PropertiesUtils.createProperties();
		try (InputStream inputStream = Transformer.class.getClassLoader()
			.getResourceAsStream(resourceRef)) {
			if (Objects.nonNull(inputStream)) {
				properties.load(inputStream);
			}
		}
		return properties;
	}

	//

	private final PrintStream sysOut;

	public PrintStream getSystemOut() {
		return sysOut;
	}

	private final PrintStream sysErr;

	public PrintStream getSystemErr() {
		return sysErr;
	}

	//

	private final Options appOptions;

	public Options getAppOptions() {
		return appOptions;
	}

	private Function<String, URL>	ruleLoader;
	private Map<String, String>		optionDefaults;

	private final String[]			args;
	private final CommandLine		parsedArgs;

	private Changes					lastActiveChanges;

	public Changes getLastActiveChanges() {
		return lastActiveChanges;
	}

	/**
	 * Set default resource references for the several 'RULE" options. Values
	 * are located relative to the option loader class.
	 *
	 * @param ruleLoader The class relative to which to load the default
	 *            resources.
	 * @param optionDefaults Table ot default resource references.
	 */
	public void setOptionDefaults(Function<String, URL> ruleLoader, Map<String, String> optionDefaults) {
		this.ruleLoader = ruleLoader;
		this.optionDefaults = new HashMap<>(optionDefaults);
	}

	@Override
	public Function<String, URL> getRuleLoader() {
		return ruleLoader;
	}

	public Map<String, String> getOptionDefaults() {
		return optionDefaults;
	}

	@Override
	public String getDefaultValue(AppOption option) {
		Map<String, String> useDefaults = getOptionDefaults();
		return (useDefaults == null) ? null : useDefaults.get(option.getLongTag());
	}

	protected String[] getArgs() {
		return args;
	}

	protected CommandLine getParsedArgs() {
		return parsedArgs;
	}

	@Override
	public String getInputFileName() {
		String[] useArgs = getParsedArgs().getArgs();
		if (useArgs != null) {
			if (useArgs.length > 0) {
				return useArgs[0]; // First argument
			}
		}
		return null;
	}

	@Override
	public String getOutputFileName() {
		String[] useArgs = getParsedArgs().getArgs();
		if (useArgs != null) {
			if (useArgs.length > 1) {
				return useArgs[1]; // Second argument
			}
		}
		return null;
	}

	@Override
	public boolean hasOption(AppOption option) {
		return getParsedArgs().hasOption(option.getShortTag());
	}

	@Override
	public String getOptionValue(AppOption option) {
		if (hasOption(option)) {
			return getParsedArgs().getOptionValue(option.getShortTag());
		}
		return null;
	}

	@Override
	public List<String> getOptionValues(AppOption option) {
		if (hasOption(option)) {
			return new ArrayList<>(Arrays.asList(getParsedArgs().getOptionValues(option.getShortTag())));
		}
		return null;
	}

	//

	private void usage(PrintStream helpStream) {
		helpStream.println("Usage: " + getClass().getName() + " input [ output ] [ options ]");
		helpStream.println();
		helpStream
			.println("Use option [ " + AppOption.HELP.getShortTag() + " ] or [ " + AppOption.HELP.getLongTag()
				+ " ] to display help information.");
		helpStream.flush();
	}

	private void help(PrintStream helpStream) {
		try (PrintWriter helpWriter = new PrintWriter(helpStream)) {
			helpWriter.println();

			HelpFormatter helpFormatter = new HelpFormatter();
			boolean AUTO_USAGE = true;
			helpFormatter.printHelp(helpWriter, HelpFormatter.DEFAULT_WIDTH + 5,
				getClass().getName()
					+ " input [ output ] [ options ]", // Command
																				// line
																				// syntax
				"Options:", // Header
				getAppOptions(), HelpFormatter.DEFAULT_LEFT_PAD, HelpFormatter.DEFAULT_DESC_PAD, "", // Footer
				!AUTO_USAGE);

			helpWriter.println();
			helpWriter.println("Actions:");
			for (ActionType actionType : ActionType.values()) {
				helpWriter.println("  [ " + actionType.name() + " ]");
			}

			helpWriter.println();
			helpWriter.println("Logging Properties:");
			for (LoggerProperty loggerProperty : LoggerProperty
				.values()) {
				helpWriter.println("  [ " + loggerProperty + " ]");
			}

			helpWriter.flush();
		}
	}

	//

	private ResultCode logResult(ResultCode rc) {
		getLogger().info(consoleMarker, "TransformerCLI Return Code [ {} ] [ {} ]", rc.ordinal(), rc);
		return rc;
	}

	public ResultCode run() {
		displayHeader();

		if (getParsedArgs() == null) {
			help(getSystemOut());
			return logResult(ResultCode.ARGS_ERROR_RC);
		}

		if (getParsedArgs().getArgs().length > 2) {
			getLogger().error(consoleMarker, "Maximum number of arguments is 2");
			return logResult(ResultCode.ARGS_ERROR_RC);
		}

		// TODO: Are the USAGE and HELP return codes correct?
		//
		// See issue #298

		if ((getArgs().length == 0) || hasOption(AppOption.USAGE)) {
			usage(getSystemOut());
			return logResult(ResultCode.SUCCESS_RC);
		} else if (hasOption(AppOption.HELP)) {
			help(getSystemOut());
			return logResult(ResultCode.SUCCESS_RC);
		}

		try {
			Transformer transformer = new Transformer(getLogger(), this);
			ResultCode rc = transformer.run();
			lastActiveChanges = transformer.getLastActiveChanges();
			// Do *NOT* log this result: If 'run' completes successfully
			// it will log the result itself.
			return rc;

			// 'run' *should* catch and handle all exceptions and
			// convert them into an error return code.
		} catch (Throwable th) {
			getLogger().error(consoleMarker, "Unexpected failure:", th);
			return logResult(ResultCode.TRANSFORM_ERROR_RC);
		}
	}
}
