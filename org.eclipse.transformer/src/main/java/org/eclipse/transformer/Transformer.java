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

package org.eclipse.transformer;

import static aQute.bnd.exceptions.BiFunctionWithException.asBiFunction;
import static java.util.Objects.requireNonNull;
import static org.eclipse.transformer.util.FileUtils.DEFAULT_CHARSET;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import aQute.bnd.unmodifiable.Sets;
import aQute.lib.io.IO;
import aQute.lib.strings.Strings;
import aQute.libg.uri.URIUtil;
import org.eclipse.transformer.action.Action;
import org.eclipse.transformer.action.ActionContext;
import org.eclipse.transformer.action.ActionSelector;
import org.eclipse.transformer.action.ActionType;
import org.eclipse.transformer.action.BundleData;
import org.eclipse.transformer.action.Changes;
import org.eclipse.transformer.action.ContainerAction;
import org.eclipse.transformer.action.ContainerChanges;
import org.eclipse.transformer.action.SelectionRule;
import org.eclipse.transformer.action.SignatureRule;
import org.eclipse.transformer.action.impl.ActionSelectorImpl;
import org.eclipse.transformer.action.impl.BundleDataImpl;
import org.eclipse.transformer.action.impl.ClassActionImpl;
import org.eclipse.transformer.action.impl.DirectoryActionImpl;
import org.eclipse.transformer.action.impl.JSPActionImpl;
import org.eclipse.transformer.action.impl.JavaActionImpl;
import org.eclipse.transformer.action.impl.ManifestActionImpl;
import org.eclipse.transformer.action.impl.PropertiesActionImpl;
import org.eclipse.transformer.action.impl.RenameActionImpl;
import org.eclipse.transformer.action.impl.SelectionRuleImpl;
import org.eclipse.transformer.action.impl.ServiceLoaderConfigActionImpl;
import org.eclipse.transformer.action.impl.SignatureRuleImpl;
import org.eclipse.transformer.action.impl.TextActionImpl;
import org.eclipse.transformer.action.impl.XmlActionImpl;
import org.eclipse.transformer.action.impl.ZipActionImpl;
import org.eclipse.transformer.util.PropertiesUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class Transformer {
	public enum ResultCode {
		SUCCESS_RC("Success"),
		ARGS_ERROR_RC("Argument Error"),
		RULES_ERROR_RC("Rules Error"),
		TRANSFORM_ERROR_RC("Transform Error"),
		FILE_TYPE_ERROR_RC("File Type Error");

		private final String description;

		ResultCode(String description) {
			this.description = description;
		}

		@Override
		public String toString() {
			return description;
		}
	}

	//

	public static final Marker		consoleMarker	= MarkerFactory.getMarker("console");
	private final Logger			logger;
	private final TransformOptions options;

	public Transformer(TransformOptions options) {
		this(LoggerFactory.getLogger(Transformer.class), options);
	}

	public Transformer(Logger logger, TransformOptions options) {
		this.logger = requireNonNull(logger);
		this.options = requireNonNull(options);
	}

	/**
	 * Base URI for transformer. Initialize to current working directory.
	 */
	private URI								base	= IO.work.toURI();

	public Map<String, String>				includes;
	public Map<String, String>				excludes;

	public boolean							invert;
	public Map<String, String>				packageRenames;
	public Map<String, String>				packageVersions;
	public Map<String, Map<String, String>> specificPackageVersions;
	public Map<String, BundleData>			bundleUpdates;
	public Map<String, String>				masterSubstitutionRefs;
	public Map<String, Map<String, String>>	masterTextUpdates;
	// ( pattern -> ( initial-> final ) )

	public Map<String, String>				directStrings;

	public boolean							widenArchiveNesting;
	private ActionSelector					actionSelector;
	public Action							acceptedAction;

	public String							inputName;
	public String							inputPath;
	public File								inputFile;

	public boolean							allowOverwrite;

	public String							outputName;
	public String							outputPath;
	public File								outputFile;
	public Map<String, Map<String, String>> perClassConstantStrings;

	//

	public Logger getLogger() {
		return logger;
	}

	private void logMerge(String sourceName, String sinkName, Object key, Object oldValue, Object newValue) {
		if (oldValue != null) {
			getLogger().debug(consoleMarker,
				"Merge of [ {} ] into [ {} ], key [ {} ] replaces value [ {} ] with [ {} ]", sourceName, sinkName, key,
				oldValue, newValue);
		}
	}

	public ResultCode run() {
		ResultCode rc = basicRun();
		getLogger().info(consoleMarker, "Transformer Return Code [ {} ] [ {} ]", rc.ordinal(), rc);
		return rc;
	}

	protected ResultCode basicRun() {
		if (!setInput()) {
			return ResultCode.TRANSFORM_ERROR_RC;
		}
		if (!setOutput()) {
			return ResultCode.TRANSFORM_ERROR_RC;
		}

		boolean loadedRules;
		try {
			loadedRules = setRules(getImmediateData());
		} catch (Exception e) {
			getLogger().error(consoleMarker, "Exception loading rules:", e);
			return ResultCode.RULES_ERROR_RC;
		}
		if (!loadedRules) {
			getLogger().error(consoleMarker, "Transformation rules cannot be used");
			return ResultCode.RULES_ERROR_RC;
		}
		logRules();

		if (!acceptAction()) {
			getLogger().error(consoleMarker, "No action selected");
			return ResultCode.FILE_TYPE_ERROR_RC;
		}

		// TODO: Need a better way to handle errors.
		//
		// (1) Having element actions throw an exception while
		// having container actions count failures makes for
		// two different error detection steps.
		//
		// (2) Handling 'Throwable' cases is problematic. These
		// should perhaps be thrown to the transformer and cause
		// transformation to fail.
		//
		// See issue #301.

		try {
			transform();
		} catch (TransformException e) {
			getLogger().error(consoleMarker, "Transform failure", e);
			return ResultCode.TRANSFORM_ERROR_RC;
		} catch (Throwable th) {
			getLogger().error(consoleMarker, "Unexpected failure", th);
			return ResultCode.TRANSFORM_ERROR_RC;
		}

		Changes lastActiveChanges = getLastActiveChanges();
		if (lastActiveChanges instanceof ContainerChanges containerChanges) {
			int numDuplicated = containerChanges.getAllDuplicated();
			int numFailed = containerChanges.getAllFailed();

			if (numDuplicated != 0) {
				getLogger().warn("Duplicates were processed [ {} ]", numDuplicated);
			}
			if (numFailed != 0) {
				getLogger().warn("Failures were processed [ {} ]", numFailed);
				return ResultCode.TRANSFORM_ERROR_RC;
			}
		}
		return ResultCode.SUCCESS_RC;
	}

	//

	public String getInputFileName() {
		return inputName;
	}

	public String getOutputFileName() {
		return outputName;
	}

	public static final Set<AppOption> TARGETABLE_RULES = Sets.of(AppOption.RULES_SELECTIONS, AppOption.RULES_RENAMES,
		AppOption.RULES_VERSIONS, AppOption.RULES_DIRECT, AppOption.RULES_PER_CLASS_CONSTANT, AppOption.RULES_BUNDLES,
		AppOption.RULES_MASTER_TEXT);

	public AppOption getTargetOption(String targetText) {
		for (AppOption appOption : TARGETABLE_RULES) {
			if (targetText.equals(appOption.getLongTag())) {
				return appOption;
			}
			if (targetText.equals(appOption.getShortTag())) {
				return appOption;
			}
		}
		return null;
	}

	/**
	 * Fetch all immediate option data from the command line. Organize the data
	 * into separate objects, one object per specific immediate option on the
	 * command line.
	 *
	 * @return Grouped immediate option data from the command line.
	 */
	public ImmediateRuleData[] getImmediateData() {
		if (!options.hasOption(AppOption.RULES_IMMEDIATE_DATA)) {
			return new ImmediateRuleData[] {
				// EMPTY
			};
		}

		List<String> immediateArgs = options.getOptionValues(AppOption.RULES_IMMEDIATE_DATA);

		if ((immediateArgs.size() % 3) != 0) {
			getLogger().error(consoleMarker, "Incorrect number of arguments to option [ {} ] [ {} ]",
				AppOption.RULES_IMMEDIATE_DATA.getLongTag(), immediateArgs);
			return null;
		}

		int argCount = immediateArgs.size() / 3;

		ImmediateRuleData[] immediateData = new ImmediateRuleData[argCount];

		for (int argNo = 0; argNo < argCount; argNo++) {
			int baseNo = argNo * 3;
			String targetText = immediateArgs.get(baseNo);
			String key = immediateArgs.get(baseNo + 1);
			String value = immediateArgs.get(baseNo + 2);

			getLogger().info(consoleMarker, "Immediate rule data specified; target [ {} ], key [ {} ], value [ {} ]",
				targetText, key, value);

			AppOption target = getTargetOption(targetText);
			if (target == null) {
				getLogger().error(consoleMarker, "Immediate rules target [ {} ] is not valid.", targetText);
				return null;
			}

			immediateData[argNo] = new ImmediateRuleData(target, key, value);
		}

		return immediateData;
	}

	/**
	 * Process the rules data. Load and validate the data.
	 *
	 * @param immediateData Immediate rules data.
	 * @return True or false telling if the data was successfully loaded and is
	 *         usable.
	 * @throws Exception Thrown if an error occurred while loading or validating
	 *             the data.
	 */
	public boolean setRules(ImmediateRuleData[] immediateData) throws Exception {
		if ( immediateData == null ) {
			return false;
		}

		Set<String> orphanedFinalPackages = new HashSet<>();

		Map<String, String> selectionProperties = loadProperties(AppOption.RULES_SELECTIONS, null);
		Map<String, String> renameProperties = loadProperties(AppOption.RULES_RENAMES, orphanedFinalPackages);
		Map<String, String> versionProperties = loadProperties(AppOption.RULES_VERSIONS, null);
		Map<String, String> updateProperties = loadProperties(AppOption.RULES_BUNDLES, null);
		Map<String, String> directProperties = loadProperties(AppOption.RULES_DIRECT, null);
		Map<String, String> textMasterProperties = loadProperties(AppOption.RULES_MASTER_TEXT, null);
		Map<String, String> perClassConstantProperties = loadProperties(AppOption.RULES_PER_CLASS_CONSTANT,
			null);

		invert = options.hasOption(AppOption.INVERT);

		if ( !selectionProperties.isEmpty() ) {
			includes = new HashMap<>();
			excludes = new HashMap<>();
			TransformProperties.addSelections(includes, excludes, selectionProperties);
			getLogger().info(consoleMarker, "Selection rules are in use");
		} else {
			includes = null;
			excludes = null;
		}

		if ( !renameProperties.isEmpty() ) {
			if (invert) {
				renameProperties = TransformProperties.invert(renameProperties);
			}
			packageRenames = renameProperties;
			getLogger().info(consoleMarker, "Package renames are in use");
		} else {
			packageRenames = null;
		}

		if ( !versionProperties.isEmpty() ) {
			packageVersions = new HashMap<>( versionProperties.size() );
			specificPackageVersions = new HashMap<>();
			TransformProperties.setPackageVersions(versionProperties, packageVersions, specificPackageVersions);
			getLogger().info(consoleMarker, "Package versions will be updated");
		} else {
			packageVersions = null;
			specificPackageVersions = null;
		}

		if ( !updateProperties.isEmpty() ) {
			bundleUpdates = TransformProperties.getBundleUpdates(updateProperties);
			getLogger().info(consoleMarker, "Bundle identities will be updated");
		} else {
			bundleUpdates = null;
		}

		String masterTextRef;

		if ( !textMasterProperties.isEmpty() ) {
			masterTextRef = options.normalize(options.getOptionValue(AppOption.RULES_MASTER_TEXT));

			Map<String, Map<String, String>> masterUpdates = new HashMap<>();
			for (Map.Entry<String, String> substitutionRefEntry : textMasterProperties.entrySet()) {
				String simpleNameSelector = substitutionRefEntry.getKey();
				String substitutionsRef = options.normalize(substitutionRefEntry.getValue());

				Map<String, String> substitutions =
					loadSubstitutions(masterTextRef, simpleNameSelector, substitutionsRef);
				if (invert) {
					substitutions = TransformProperties.invert(substitutions);
				}

				textMasterProperties.put(simpleNameSelector, substitutionsRef);
				masterUpdates.put(simpleNameSelector, substitutions);
			}

			masterSubstitutionRefs = textMasterProperties;
			masterTextUpdates = masterUpdates;
			getLogger().info(consoleMarker, "Text files will be updated");
		} else {
			masterTextRef = null;
			masterTextUpdates = null;
		}

		if ( !directProperties.isEmpty() ) {
			if (invert) {
				directProperties = TransformProperties.invert(directProperties);
			}
			directStrings = directProperties;
			getLogger().info(consoleMarker, "Java direct string updates will be performed");
		} else {
			directStrings = null;
			getLogger().debug(consoleMarker, "Java direct string updates will not be performed");
		}

		if ( !perClassConstantProperties.isEmpty() ) {
			String masterDirect = options.normalize(options.getOptionValue(AppOption.RULES_PER_CLASS_CONSTANT));

			Map<String, Map<String, String>> masterUpdates = new HashMap<>();
			for (Map.Entry<String, String> substitutionRefEntry : perClassConstantProperties.entrySet()) {
				String classSelector = substitutionRefEntry.getKey();
				String substitutionsRef = options.normalize(substitutionRefEntry.getValue());

				Map<String, String> substitutions = loadSubstitutions(masterDirect, classSelector, substitutionsRef);
				if (invert) {
					substitutions = TransformProperties.invert(substitutions);
				}
				masterUpdates.put(classSelector, substitutions);
			}

			perClassConstantStrings = masterUpdates;
			getLogger().info(consoleMarker, "Per class constant mapping files are enabled");

		} else {
			perClassConstantStrings = null;
			getLogger().debug(consoleMarker, "Per class constant mapping files are not enabled");
		}

		processImmediateData(immediateData, masterTextRef, orphanedFinalPackages);

		// Delay reporting null property sets: These are assigned directly
		// and by immediate data.

		if ( includes == null ) {
			getLogger().info(consoleMarker, "All resources will be selected");
		}
		if ( packageRenames == null ) {
			getLogger().debug(consoleMarker, "Packages will not be renamed");
		}
		if ( packageVersions == null ) {
			getLogger().debug(consoleMarker, "Package versions will not be updated");
		}
		if ( bundleUpdates == null ) {
			getLogger().debug(consoleMarker, "Bundle identities will not be updated");
		}
		if ( masterTextUpdates == null ) {
			getLogger().debug(consoleMarker, "Text files will not be updated");
		}
		if ( directStrings == null ) {
			getLogger().debug(consoleMarker, "Java direct string updates will not be performed");
		}
		if ( perClassConstantStrings == null ) {
			getLogger().debug(consoleMarker, "Per class constant mapping files are not enabled");
		}

		return validateVersionUpdates(orphanedFinalPackages);
	}

	protected void processImmediateData(
		ImmediateRuleData[] immediateData, String masterTextRef,
		Set<String> orphanedFinalVersions)
		throws IOException, URISyntaxException {

		for ( ImmediateRuleData nextData : immediateData ) {
			switch ( nextData.target() ) {
				case RULES_SELECTIONS:
					addImmediateSelection(nextData.key(), nextData.value());
					break;
				case RULES_RENAMES:
					addImmediateRename(nextData.key(), nextData.value(), orphanedFinalVersions);
					break;
				case RULES_VERSIONS:
					addImmediateVersion(nextData.key(), nextData.value());
					break;
				case RULES_BUNDLES:
					addImmediateBundleData(nextData.key(), nextData.value());
					break;
				case RULES_DIRECT:
					addImmediateDirect(nextData.key(), nextData.value());
					break;
				case RULES_MASTER_TEXT:
					addImmediateMasterText(masterTextRef, nextData.key(), nextData.value());
					break;

				default:
					getLogger().error(consoleMarker, "Unrecognized immediate data target [ {} ]", nextData.target());
			}
		}
	}

	private void addImmediateSelection(String selection, String charset) {
		if ( includes == null ) {
			includes = new HashMap<>();
			includes.put("*", DEFAULT_CHARSET.name()); // set default inclusion
			excludes = new HashMap<>();
			getLogger().info(consoleMarker, "Selection rules use forced by immediate data");
		}

		TransformProperties.addSelection(includes, excludes, selection, charset);
	}

	private void addImmediateRename(
		String initialPackageName, String finalPackageName,
		Set<String> orphanedFinalPackages) {

		if ( packageRenames == null ) {
			packageRenames = new HashMap<>();
			getLogger().info(consoleMarker, "Package renames forced by immediate data.");
		}

		if ( invert ) {
			String initialHold = initialPackageName;
			initialPackageName = finalPackageName;
			finalPackageName = initialHold;
		}

		String oldFinalPackageName = packageRenames.put(initialPackageName, finalPackageName);

		processOrphan(
			"immediate rename data", "renameData",
			initialPackageName, oldFinalPackageName, finalPackageName,
			orphanedFinalPackages);

		logMerge(
			"immediate rename data", "rename data",
			initialPackageName, oldFinalPackageName, finalPackageName);
	}

	private void addImmediateVersion(String finalPackageName, String versionText) {
		if ( packageVersions == null ) {
			packageVersions = new HashMap<>();
			specificPackageVersions = new HashMap<>();
			getLogger().info(consoleMarker, "Package version updates forced by immediate data.");
		}
		TransformProperties.setPackageVersions(finalPackageName, versionText, packageVersions, specificPackageVersions);
	}

	private void addImmediateBundleData(String bundleId, String value) {
		if ( bundleUpdates == null ) {
			bundleUpdates = new HashMap<>();
			getLogger().info(consoleMarker, "Bundle identity updates forced by immediate data.");
		}
		BundleData newBundleData = new BundleDataImpl(value);
		BundleData oldBundleData = bundleUpdates.put(bundleId, newBundleData);
		if ( oldBundleData != null ) {
			logMerge("immediate bundle data", "bundle data", bundleId, oldBundleData.getPrintString(),
				newBundleData.getPrintString());
		}
	}

	private void addImmediateDirect(String initialText, String finalText) {
		if ( directStrings == null ) {
			directStrings = new HashMap<>();
			getLogger().info(consoleMarker, "Java direct string updates forced by immediate data");
		}

		String oldFinalText = directStrings.put(initialText, finalText);
		if ( oldFinalText != null ) {
			logMerge("immediate direct string data", "direct string data", initialText, oldFinalText,
				finalText);
		}
	}

	//

	/**
	 * Load properties for the specified rule option. Answer an empty collection
	 * if the rule option was not provided. Options loading tries
	 * {@link TransformOptions#getOptionValue(AppOption)} then tries
	 * {@link TransformOptions#getDefaultValue(AppOption)}. An empty
	 * collection is returned when neither is available. Orphaned values are
	 *
	 * @param ruleOption The option for which to load properties.
	 * @param orphanedValues Values which have been orphaned by merges.
	 * @return Properties loaded using the reference set for the option.
	 * @throws IOException Thrown if the load failed.
	 * @throws URISyntaxException Thrown if the load failed because a non-valid
	 *             URI was specified.
	 */
	public Map<String, String> loadProperties(AppOption ruleOption, Set<String> orphanedValues)
		throws IOException, URISyntaxException {
		List<String> rulesReferences = options.normalize(options.getOptionValues(ruleOption));

		if (rulesReferences == null) {
			String rulesReference = options.getDefaultValue(ruleOption);
			if (rulesReference == null) {
				getLogger().debug(consoleMarker, "Skipping option [ {} ]", ruleOption);
				rulesReferences = Collections.emptyList();
			} else {
				rulesReferences = Collections.singletonList(rulesReference);
			}
		}
		if (rulesReferences.isEmpty()) {
			return new HashMap<>();
		}
		String referenceName = ruleOption.name();
		String baseReference = rulesReferences.get(0);
		Map<String, String> mergedProperties = rulesReferences.stream()
			.reduce(new HashMap<>(), asBiFunction((props, ref) -> {
				Properties p = loadProperties0(referenceName, ref);
				merge(baseReference, props, ref, p, orphanedValues);
				return props;
			}), (props1, props2) -> {
				props1.putAll(props2);
				return props1;
			});
		return mergedProperties;
	}

	private static final URI EMPTYURI = URI.create("");

	String relativize(String relativeRef, String baseRef) throws URISyntaxException {
		URI baseURI = URIUtil.resolve(EMPTYURI, baseRef);
		URI resolved = URIUtil.resolve(baseURI, relativeRef);
		return resolved.toString();
	}

	protected Properties loadProperties0(String referenceName, String reference)
		throws URISyntaxException, IOException {
		URL url;
		URI uri = URIUtil.resolve(EMPTYURI, reference);
		if (uri.isAbsolute()) {
			// reference has a scheme
			url = uri.toURL();
		} else {
			// First resolve against base URI
			URI fileUri = getBase().resolve(uri);
			if (Files.exists(Paths.get(fileUri))) {
				url = fileUri.toURL();
			} else {
				// Ask the TransformOptions to load the reference
				url = options.getRuleLoader()
					.apply(reference);
				if (url == null) {
					getLogger().debug(consoleMarker, "Resource [ {} ] was not found [ {} ]", reference, referenceName);
					throw new IOException(
						"Resource [ " + reference + " ] not found on [ " + options.getRuleLoader() + " ]");
				}
			}
		}
		getLogger().info(consoleMarker, "Properties [ {} ] URL [ {} ]", referenceName, url);

		return PropertiesUtils.loadProperties(url);
	}

	protected void merge(String sinkName, Map<String, String> sink, String sourceName, Properties source,
		Set<String> orphanedValues) {

		for (Map.Entry<Object, Object> sourceEntry : source.entrySet()) {
			String key = (String) sourceEntry.getKey();
			String newValue = (String) sourceEntry.getValue();
			String oldValue = sink.put(key, newValue);

			if (orphanedValues != null) {
				processOrphan(sourceName, sinkName, key, oldValue, newValue, orphanedValues);
			}

			logMerge(sourceName, sinkName, key, oldValue, newValue);
		}
	}

	/**
	 * Detect orphaned and un-orphaned property assignments. An orphaned
	 * property assignment occurs when a new property assignment changes the
	 * assigned property value. An orphaned value become un-orphaned if a
	 * property assignment has that value.
	 *
	 * @param sourceName A name associated with the properties which were added.
	 *            Used for logging.
	 * @param sinkName A name associated with the properties into which the
	 *            source properties were added. Used for logging.
	 * @param key The key which was overridden by the source properties.
	 * @param oldValue The value previously assigned to the key in the sink
	 *            properties.
	 * @param newValue The value newly assigned to the key in the sink
	 *            properties.
	 * @param orphans Accumulated sink property values which were orphaned.
	 */
	protected void processOrphan(String sourceName, String sinkName, String key, String oldValue, String newValue,
		Set<String> orphans) {

		if ((oldValue != null) && oldValue.equals(newValue)) {
			return; // Nothing to do: The old and new assignments are the same.
		}

		if (oldValue != null) {
			getLogger().debug(consoleMarker, "Merge of [ {} ] into [ {} ], key [ {} ] orphans [ {} ]", sourceName,
				sinkName, key, oldValue);
			orphans.add(oldValue);
		}

		if (orphans.remove(newValue)) {
			getLogger().debug(consoleMarker, "Merge of [ {} ] into [ {} ], key [ {} ] un-orphans [ {} ]", sourceName,
				sinkName, key, newValue);
		}
	}

	private Map<String, String> loadSubstitutions(String masterRef, String selector, String substitutionsRef) {
		String referenceName = "Substitutions matching [ " + selector + " ]";
		List<String> substitutionsRefs = Strings.split(substitutionsRef);

		Map<String, String> substitutions = substitutionsRefs.stream()
			.reduce(new HashMap<>(), asBiFunction((props, ref) -> {
				String relativeSubstitutionsRef = (masterRef != null) ? relativize(ref, masterRef) : ref;
				if (!relativeSubstitutionsRef.equals(ref)) {
					getLogger().debug(consoleMarker, "Adjusted substitution reference from [ {} ] to [ {} ]", ref,
						relativeSubstitutionsRef);
				}
				Properties p = loadProperties0(referenceName, relativeSubstitutionsRef);
				return TransformProperties.copyPropertiesToMap(p, props);
			}), (props1, props2) -> {
				props1.putAll(props2);
				return props1;
			});

		return substitutions;
	}

	private void addImmediateMasterText(
		String masterTextRef, String simpleNameSelector, String substitutionsRef)
		throws IOException, URISyntaxException {

		if ( masterTextUpdates == null ) {
			masterTextUpdates = new HashMap<>();
			getLogger().info(consoleMarker, "Text files updates forced by immediate data.");
		}

		substitutionsRef = options.normalize(substitutionsRef);

		Map<String, String> substitutionsMap =
			loadSubstitutions(masterTextRef, simpleNameSelector, substitutionsRef);

		String oldSubstitutionsRef =
			masterSubstitutionRefs.put(simpleNameSelector, substitutionsRef);
		@SuppressWarnings("unused")
		Map<String, String> oldSubstitutionMap =
			masterTextUpdates.put(simpleNameSelector, substitutionsMap);

		if ( oldSubstitutionsRef != null ) {
			logMerge("immediate master text data", "master text data", simpleNameSelector,
				oldSubstitutionsRef,
				substitutionsRef);
		}
	}

	/**
	 * Validate package version updates.  Answer true or false, telling if
	 * the version updates are valid.
	 *
	 * That is, validate the generic and the specific package version updates.
	 * The version updates are valid if and only if each package version update
	 * uses a package name which is the final package name of a package rename.
	 *
	 * See {@link #validateVersionUpdates(Map, Set)}.
	 *
	 * @param orphanedFinalPackages Orphaned final packages.
	 *
	 * @return True or false, telling if the package version updates are
	 *     valid.
	 */
	protected boolean validateVersionUpdates(Set<String> orphanedFinalPackages) {
		if ( ((packageVersions == null) || packageVersions.isEmpty()) &&
			 ((specificPackageVersions == null) || specificPackageVersions.isEmpty()) ) {
			return true; // Nothing to validate
		}

		// Don't bother listing all of the missing package names if no
		// renames were specified.  A single error message is sufficient.

		if ( (packageRenames == null) || packageRenames.isEmpty() ) {
			getLogger().error(consoleMarker,
				"Package version updates were specified but no package renames were specified.");
			return false;
		}

		boolean missingFinalPackages = !validateVersionUpdates(packageVersions, orphanedFinalPackages);

		for ( Map.Entry<String, Map<String, String>> specificEntry : specificPackageVersions.entrySet() ) {
			String attributeName = specificEntry.getKey();
			Map<String, String> updatesForAttribute = specificEntry.getValue();
			if ( !validateVersionUpdates(updatesForAttribute, orphanedFinalPackages) ) {
				missingFinalPackages = true;
			}
		}

		Set<String> ignoredAttributes = null;
		for ( String attributeName : specificPackageVersions.keySet() ) {
			if ( !ManifestActionImpl.selectAttribute(attributeName) ) {
				if ( ignoredAttributes == null ) {
					ignoredAttributes = new HashSet<>();
				}
				ignoredAttributes.add(attributeName);
			}
		}
		if ( ignoredAttributes != null ) {
			getLogger().info(consoleMarker,
				"Warning: Ignoring unknown attributes {} used for specific package version updates.",
				ignoredAttributes);
		}

		return !missingFinalPackages;
	}

	protected boolean validateVersionUpdates(Map<String, String> versionUpdates, Set<String> orphanedFinalPackages) {
		boolean isValid = true;
		for ( String finalPackage : versionUpdates.keySet() ) {
			// Keep looping even after a non-valid update is detected:
			// Emit error messages for all problem version updates.
			if ( !validateVersionUpdate(finalPackage, orphanedFinalPackages) ) {
				isValid = false;
			}
		}
		return isValid;
	}

	/**
	 * Validate a package version update against the specified package renames.
	 * Answer true or false, telling if the update is valid.
	 * <p>
	 * There are three cases:
	 * <p>
	 * The version update uses a package which is the final package in a package
	 * rename rule. The update is valid.
	 * <p>
	 * The version update uses a package which has been orphaned by a package
	 * rename rule. That is, the version update would be valid if the final
	 * package was not orphaned. The update is considered valid.
	 * <p>
	 * The version update uses a package which was not specified as the final
	 * package in a package rename rule. The update is valid.
	 *
	 * @param finalPackage The package which was used as the key to a package
	 *            version update. This package must be used as the final package
	 *            of a package rename.
	 * @param orphanedFinalPackages Final packages which were orphaned.
	 * @return True or false, telling if the package version update is valid.
	 */
	protected boolean validateVersionUpdate(String finalPackage, Set<String> orphanedFinalPackages) {
		if ( packageRenames.containsValue(finalPackage) ) {
			return true;
		}

		// This orphaned case is curious:
		//
		// False might be returned, because the version update will never be used.
		//
		// However, true is returned, since we want to allow overrides which create
		// orphans.

		if ( orphanedFinalPackages.contains(finalPackage) ) {
			getLogger().info(consoleMarker, "Package [ {} ] has a version update but was orphaned.", finalPackage);
			return true;
		}

		getLogger().info(consoleMarker, "Package [ {} ] has a version update but was not renamed.", finalPackage);
		return true;
	}

	public void logRules() {
		if (!getLogger().isDebugEnabled()) {
			return;
		}
		getLogger().debug("Includes:");
		if ((includes == null) || includes.isEmpty()) {
			getLogger().debug("  [ ** NONE ** ]");
		} else {
			includes.forEach((include, charset) -> getLogger().debug("  [ {} ] [ {} ]", include, charset));
		}

		getLogger().debug("Excludes:");
		if ((excludes == null) || excludes.isEmpty()) {
			getLogger().debug("  [ ** NONE ** ]");
		} else {
			for (String exclude : excludes.keySet()) {
				getLogger().debug("  [ {} ]", exclude);
			}
		}

		if (invert) {
			getLogger().debug("Package Renames: [ ** INVERTED ** ]");
		} else {
			getLogger().debug("Package Renames:");
		}

		if ((packageRenames == null) || packageRenames.isEmpty()) {
			getLogger().debug("  [ ** NONE ** ]");
		} else {
			for (Map.Entry<String, String> renameEntry : packageRenames.entrySet()) {
				getLogger().debug("  [ {} ]: [ {} ]", renameEntry.getKey(), renameEntry.getValue());
			}
		}

		getLogger().debug("Package Versions:");
		if ((packageVersions == null) || packageVersions.isEmpty()) {
			getLogger().debug("  [ ** NONE ** ]");
		} else {
			for (Map.Entry<String, String> versionEntry : packageVersions.entrySet()) {
				getLogger().debug("  [ {} ]: [ {} ]", versionEntry.getKey(), versionEntry.getValue());
			}
		}

		getLogger().debug("Bundle Updates:");
		if ((bundleUpdates == null) || bundleUpdates.isEmpty()) {
			getLogger().debug("  [ ** NONE ** ]");
		} else {
			for (Map.Entry<String, BundleData> updateEntry : bundleUpdates.entrySet()) {
				BundleData updateData = updateEntry.getValue();

				getLogger().debug("  [ {} ]: [ {} ]", updateEntry.getKey(), updateData.getSymbolicName());

				getLogger().debug("    [ Version ]: [ {} ]", updateData.getVersion());

				if (updateData.getAddName()) {
					getLogger().debug("    [ Name ]: [ " + BundleData.ADDITIVE_CHAR + "{} ]", updateData.getName());
				} else {
					getLogger().debug("    [ Name ]: [ {} ]", updateData.getName());
				}

				if (updateData.getAddDescription()) {
					getLogger().debug(
						"    [ Description ]: [ " + BundleData.ADDITIVE_CHAR + "{} ]", updateData.getDescription());
				} else {
					getLogger().debug("    [ Description ]: [ {} ]", updateData.getDescription());
				}
			}
		}

		getLogger().debug("Java string substitutions:");
		if ((directStrings == null) || directStrings.isEmpty()) {
			getLogger().debug("  [ ** NONE ** ]");
		} else {
			for (Map.Entry<String, String> directEntry : directStrings.entrySet()) {
				getLogger().debug("  [ {} ]: [ {} ]", directEntry.getKey(), directEntry.getValue());
			}
		}

		getLogger().debug("Text substitutions:");
		if ((masterTextUpdates == null) || masterTextUpdates.isEmpty()) {
			getLogger().debug("  [ ** NONE ** ]");
		} else {
			for (Map.Entry<String, Map<String, String>> masterTextEntry : masterTextUpdates.entrySet()) {
				getLogger().debug("  Pattern [ {} ]", masterTextEntry.getKey());
				for (Map.Entry<String, String> substitution : masterTextEntry.getValue()
					.entrySet()) {
					getLogger().debug("    [ {} ]: [ {} ]", substitution.getKey(), substitution.getValue());
				}
			}
		}
	}

	private SelectionRule selectionRules;

	public SelectionRule getSelectionRule() {
		if (selectionRules == null) {
			selectionRules = new SelectionRuleImpl(getLogger(), includes, excludes);
		}
		return selectionRules;
	}

	private SignatureRule signatureRules;

	public SignatureRule getSignatureRule() {
		if (signatureRules == null) {
			signatureRules = new SignatureRuleImpl(
				getLogger(),
				packageRenames, packageVersions, specificPackageVersions,
				bundleUpdates,
				masterTextUpdates, directStrings, perClassConstantStrings);
		}
		return signatureRules;
	}

	public boolean setInput() {
		String useInputName = options.getInputFileName();
		if (useInputName == null || useInputName.isEmpty()) {
			getLogger().error(consoleMarker, "No input file was specified");
			return false;
		}

		inputName = options.normalize(useInputName);
		inputFile = new File(inputName);
		inputPath = inputFile.getAbsolutePath();

		if (!inputFile.exists()) {
			getLogger().error(consoleMarker, "Input does not exist [ {} ] [ {} ]", inputName, inputPath);
			return false;
		}

		try {
			inputFile = inputFile.getCanonicalFile();
			inputPath = inputFile.getAbsolutePath();
		} catch (IOException e) {
			getLogger().error(consoleMarker, "Input error [ {} ] [ {} ] ", inputName, e.toString(), e);
			return false;
		}
		if (inputFile.getParent() == null) {
			getLogger().error(consoleMarker,
				"Input directory is invalid. Don't designate the top directory. [ {} ] [ {} ]", inputName, inputPath);
			return false;
		}

		getLogger().debug(consoleMarker, "Input [ {} ]", inputName);
		getLogger().info(consoleMarker, "Input [ {} ]", inputPath);
		return true;
	}

	public static final String OUTPUT_PREFIX = "output_";

	public boolean setOutput() {
		String useOutputName = options.getOutputFileName();
		if (useOutputName != null && useOutputName.isEmpty()) {
			getLogger().error(consoleMarker, "Output path is empty");
			return false;
		}

		boolean isExplicit = (useOutputName != null);

		if (isExplicit) {
			useOutputName = options.normalize(useOutputName);

		} else {
			File parent = inputFile.getParentFile();
			File output = new File(parent, OUTPUT_PREFIX + inputFile.getName());
			useOutputName = options.normalize(output.getAbsolutePath());
		}

		File useOutputFile = new File(useOutputName);
		String useOutputPath = null;
		try {
			useOutputFile = useOutputFile.getCanonicalFile();
			useOutputPath = useOutputFile.getAbsolutePath();
		} catch (IOException e) {
			getLogger().error(consoleMarker, "Output error [ {} ] [ {} ]", outputName, e.toString(), e);
			return false;
		}

		boolean putIntoDirectory = (inputFile.isFile() && useOutputFile.isDirectory());

		if (putIntoDirectory) {
			useOutputName = useOutputName + '/' + inputName;
			getLogger().debug(consoleMarker, "Output generated using input name and output directory [ {} ]",
				useOutputName);

			useOutputFile = new File(useOutputName);
			useOutputPath = useOutputFile.getAbsolutePath();
		}

		String outputCase;
		if (isExplicit) {
			if (putIntoDirectory) {
				outputCase = "Explicit directory";
			} else {
				outputCase = "Explicit";
			}
		} else {
			if (putIntoDirectory) {
				outputCase = "Directory generated from input";
			} else {
				outputCase = "Generated from input";
			}
		}

		getLogger().debug(consoleMarker, "Output [ {} ] ({})", useOutputName, outputCase);
		getLogger().info(consoleMarker, "Output [ {} ]", useOutputPath);

		allowOverwrite = options.hasOption(AppOption.OVERWRITE);
		if (allowOverwrite) {
			getLogger().info(consoleMarker, "Overwrite of output is enabled");
		}

		if (outputExists(useOutputFile)) {
			if (allowOverwrite) {
				getLogger().info(consoleMarker, "Output exists and will be overwritten [ {} ]", useOutputPath);
			} else {
				getLogger().error(consoleMarker, "Output already exists [ {} ]", useOutputPath);
				return false;
			}
		} else {
			if (allowOverwrite) {
				getLogger().debug(consoleMarker, "Overwritten specified, but output [ {} ] does not exist",
					useOutputPath);
			}
		}

		outputName = useOutputName;
		outputFile = useOutputFile;
		outputPath = useOutputPath;
		if (outputPath.startsWith(inputPath + File.separator)) {
			getLogger().error(consoleMarker, "Output path is under input directory [ {} ]", useOutputPath);
			return false;
		}

		return true;
	}

	private boolean outputExists(File outputFile) {
		if (outputFile.isFile()) {
			return true;
		}
		if (outputFile.isDirectory()) {
			try (Stream<Path> stream = Files.list(outputFile.toPath())) {
				return stream.findAny()
					.isPresent();
			} catch (IOException e) {
				return true;
			}
		}
		return false;
	}

	// As a separate method to allow re-use.

	public ActionContext getActionContext() {
		return new ActionContext(getLogger(), getSelectionRule(), getSignatureRule());
	}

	public ActionSelector getActionSelector() {
		if (actionSelector == null) {
			ActionSelector useSelector = new ActionSelectorImpl();
			ActionContext context = getActionContext();

			ContainerAction directoryAction = useSelector.addUsing(DirectoryActionImpl::new, context);

			Action classAction = useSelector.addUsing(ClassActionImpl::new, context);
			// The java and JSP actions must be before the text action.
			Action javaAction = useSelector.addUsing(JavaActionImpl::new, context);
			Action jspAction = useSelector.addUsing(JSPActionImpl::new, context);
			Action serviceConfigAction = useSelector.addUsing(ServiceLoaderConfigActionImpl::new, context);
			Action manifestAction = useSelector.addUsing(c -> new ManifestActionImpl(c, ActionType.MANIFEST), context);
			Action featureAction = useSelector.addUsing(c -> new ManifestActionImpl(c, ActionType.FEATURE), context);
			Action textAction = useSelector.addUsing(TextActionImpl::new, context);
			Action xmlAction = useSelector.addUsing(XmlActionImpl::new, context);
			Action propertiesAction = useSelector.addUsing(PropertiesActionImpl::new, context);

			List<Action> standardActions = new ArrayList<>();
			standardActions.add(classAction);
			standardActions.add(javaAction); // before text
			standardActions.add(jspAction); // before text
			standardActions.add(serviceConfigAction);
			standardActions.add(manifestAction);
			standardActions.add(featureAction);
			standardActions.add(textAction);
			standardActions.add(propertiesAction); // after text so text can supersede
			standardActions.add(xmlAction); // after text so text can supersede

			ContainerAction jarAction = useSelector.addUsing(c -> new ZipActionImpl(c, ActionType.JAR,
				options.hasOption(AppOption.STRIP_SIGNATURES)), context);
			ContainerAction warAction = useSelector.addUsing(c -> new ZipActionImpl(c, ActionType.WAR,
				options.hasOption(AppOption.STRIP_SIGNATURES)), context);
			ContainerAction rarAction = useSelector.addUsing(c -> new ZipActionImpl(c, ActionType.RAR,
				options.hasOption(AppOption.STRIP_SIGNATURES)), context);
			ContainerAction earAction = useSelector.addUsing(c -> new ZipActionImpl(c, ActionType.EAR,
				options.hasOption(AppOption.STRIP_SIGNATURES)), context);
			ContainerAction zipAction = useSelector.addUsing(c -> new ZipActionImpl(c, ActionType.ZIP,
				options.hasOption(AppOption.STRIP_SIGNATURES)), context);

			Action renameAction = useSelector.addUsing(RenameActionImpl::new, context);

			// Directory actions know about all actions except for directory
			// actions, and except for the properties action.

			directoryAction.addActions(standardActions);

			directoryAction.addAction(zipAction);
			directoryAction.addAction(jarAction);
			directoryAction.addAction(warAction);
			directoryAction.addAction(rarAction);
			directoryAction.addAction(earAction);

			// Container actions nest per usual JavaEE nesting rules.
			// That is, EAR can contain JAR, WAR, and RAR,
			// WAR can container JAR, and RAR can contain JAR.

			jarAction.addActions(standardActions);

			warAction.addActions(standardActions);
			warAction.addAction(jarAction);

			rarAction.addActions(standardActions);
			rarAction.addAction(jarAction);

			// TODO: Should EAR add the other standard actions?  See issue #302
			earAction.addAction(manifestAction);
			earAction.addAction(textAction);
			earAction.addAction(jarAction);
			earAction.addAction(warAction);
			earAction.addAction(rarAction);

			zipAction.addActions(standardActions);
			zipAction.addAction(jarAction);
			zipAction.addAction(warAction);
			zipAction.addAction(rarAction);
			zipAction.addAction(earAction);

			// On occasion, the JavaEE nesting rules are too
			// restrictive. Allow a slight widening of the
			// usual nesting.

			if (options.hasOption(AppOption.WIDEN_ARCHIVE_NESTING)) {
				getLogger().info(consoleMarker, "Widened action nesting is enabled.");
				jarAction.addAction(jarAction);
				jarAction.addAction(zipAction);

				rarAction.addAction(zipAction);
				warAction.addAction(zipAction);
				earAction.addAction(zipAction);

				zipAction.addAction(zipAction);
			}

			// Rename actions must be added last: The rename action
			// is always selected, which prevents selection of
			// any action added after the rename action.

			directoryAction.addAction(renameAction);
			jarAction.addAction(renameAction);
			warAction.addAction(renameAction);
			rarAction.addAction(renameAction);
			earAction.addAction(renameAction);
			zipAction.addAction(renameAction);

			actionSelector = useSelector;
		}

		return actionSelector;
	}

	public boolean acceptAction() {
		String actionName = options.getOptionValue(AppOption.FILE_TYPE);
		if (actionName != null) {
			acceptedAction = getActionSelector().acceptType(actionName);
			if (acceptedAction != null) {
				getLogger().info(consoleMarker, "Forced action [ {} ]", actionName);
				return true;
			} else {
				getLogger().error(consoleMarker, "No match for forced action [ {} ]", actionName);
				return false;
			}

		} else {
			acceptedAction = getActionSelector().selectAction(inputName, inputFile);
			if (acceptedAction == null) {
				getLogger().error(consoleMarker, "No action selected for input [ {} ]", inputName);
				return false;
			} else {
				getLogger().info(consoleMarker, "Action selected for input [ {} ]: {}", inputName,
					acceptedAction.getName());
				return true;
			}
		}
	}

	public void transform() throws TransformException {
		acceptedAction.apply(inputName, inputFile, outputName, outputFile);

		acceptedAction.getLastActiveChanges()
			.log(getLogger(), inputPath, outputPath);
	}

	public Changes getLastActiveChanges() {
		if (acceptedAction != null) {
			return acceptedAction.getLastActiveChanges();
		}
		return null;
	}

	/**
	 * Base URI for transformer. Used to resolve references to resources.
	 *
	 * @return The base URI for transformer.
	 */
	public URI getBase() {
		return base;
	}

	/**
	 * Set the base URI for transformer.
	 *
	 * @param base The base URI for transformer.
	 */
	public void setBase(URI base) {
		this.base = requireNonNull(base);
	}
}
