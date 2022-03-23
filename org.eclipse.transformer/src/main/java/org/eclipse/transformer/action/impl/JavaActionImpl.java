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

package org.eclipse.transformer.action.impl;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Map;

import org.eclipse.transformer.TransformException;
import org.eclipse.transformer.action.ActionType;
import org.eclipse.transformer.action.ByteData;
import org.eclipse.transformer.action.Changes;
import org.eclipse.transformer.action.InputBuffer;
import org.eclipse.transformer.action.SelectionRule;
import org.eclipse.transformer.action.SignatureRule;
import org.slf4j.Logger;

import aQute.lib.io.ByteBufferInputStream;
import aQute.lib.io.ByteBufferOutputStream;

public class JavaActionImpl extends ActionImpl<Changes> {

	public JavaActionImpl(Logger logger, InputBuffer buffer, SelectionRule selectionRule, SignatureRule signatureRule) {

		super(logger, buffer, selectionRule, signatureRule);
	}

	//

	@Override
	public String getName() {
		return ("Java Action");
	}

	@Override
	public ActionType getActionType() {
		return (ActionType.JAVA);
	}

	//

	@Override
	public String getAcceptExtension() {
		return ".java";
	}

	//

	/**
	 * Replace all embedded packages of specified text with replacement
	 * packages.
	 *
	 * @param text Text embedding zero, one, or more package names.
	 * @return The text with all embedded package names replaced. Null if no
	 *         replacements were performed.
	 */
	protected String replacePackages(String text) {
		// System.out.println("replacePackages: Initial text [ " + text + " ]");

		String initialText = text;

		for (Map.Entry<String, String> renameEntry : getPackageRenames().entrySet()) {
			String key = renameEntry.getKey();
			int keyLen = key.length();

			boolean matchSubpackages = SignatureRuleImpl.containsWildcard(key);
			if (matchSubpackages) {
				key = SignatureRuleImpl.stripWildcard(key);
			}

			// System.out.println("replacePackages: Next target [ " + key + "
			// ]");
			int textLimit = text.length() - keyLen;

			int lastMatchEnd = 0;
			while (lastMatchEnd <= textLimit) {
				int matchStart = text.indexOf(key, lastMatchEnd);
				if (matchStart == -1) {
					break;
				}

				if (!SignatureRuleImpl.isTruePackageMatch(text, matchStart, keyLen, matchSubpackages)) {
					lastMatchEnd = matchStart + keyLen;
					continue;
				}

				String value = renameEntry.getValue();
				int valueLen = value.length();

				String head = text.substring(0, matchStart);
				String tail = text.substring(matchStart + keyLen);

				// int tailLenBeforeReplaceVersion = tail.length();
				// tail = replacePackageVersion(tail,
				// getPackageVersions().get(value));
				// int tailLenAfterReplaceVersion = tail.length();

				text = head + value + tail;

				lastMatchEnd = matchStart + valueLen;

				// Replacing the key or the version can increase or decrease the
				// text length.
				textLimit += (valueLen - keyLen);
				// textLimit += (tailLenAfterReplaceVersion -
				// tailLenBeforeReplaceVersion);

				// System.out.println("Next text [ " + text + " ]");
			}
		}

		if (initialText == text) {
			// System.out.println("Final text is unchanged");
			return null;
		} else {
			// System.out.println("Final text [ " + text + " ]");
			return text;
		}
	}

	@Override
	public ByteData apply(ByteData inputData) throws TransformException {
		startRecording(inputData.name());
		try {
			String outputName = null;
			// String outputName = renameInput(inputName); // TODO
			// if ( outputName == null ) {
			outputName = inputData.name();
			// } else {
			// info("Input class name [ {} ]", inputName);
			// info("Output class name [ {} ]", outputName);
			// }
			setResourceNames(inputData.name(), outputName);

			InputStream inputStream = new ByteBufferInputStream(inputData.buffer());
			InputStreamReader inputReader = new InputStreamReader(inputStream, UTF_8);

			BufferedReader reader = new BufferedReader(inputReader);

			ByteBufferOutputStream outputStream = new ByteBufferOutputStream(inputData.length());
			OutputStreamWriter outputWriter = new OutputStreamWriter(outputStream, UTF_8);

			BufferedWriter writer = new BufferedWriter(outputWriter);

			try {
				transform(reader, writer);
			} catch (IOException e) {
				getLogger().error("Failed to transform [ {} ]", inputData.name(), e);
				return null;
			}

			try {
				writer.flush();
			} catch (IOException e) {
				getLogger().error("Failed to flush [ {} ]", inputData.name(), e);
				return null;
			}

			if (!hasNonResourceNameChanges()) {
				return null;
			}

			ByteData outputData = new ByteDataImpl(outputName, outputStream.toByteBuffer());
			return outputData;
		} finally {
			stopRecording(inputData.name());
		}
	}

	protected void transform(BufferedReader reader, BufferedWriter writer) throws IOException {

		String inputLine;
		while ((inputLine = reader.readLine()) != null) {
			String outputLine = replacePackages(inputLine);
			if (outputLine == null) {
				outputLine = inputLine;
			} else {
				addReplacement();
			}
			writer.write(outputLine);
			writer.write('\n');
		}
	}

	// TODO: Copied from ServiceConfigActionImpl; need to update
	// to work for paths.

	protected String renameInput(String inputName) {
		String inputPrefix;
		String classQualifiedName;

		int lastSlash = inputName.lastIndexOf('/');
		if (lastSlash == -1) {
			inputPrefix = null;
			classQualifiedName = inputName;
		} else {
			inputPrefix = inputName.substring(0, lastSlash + 1);
			classQualifiedName = inputName.substring(lastSlash + 1);
		}

		int classStart = classQualifiedName.lastIndexOf('.');
		if (classStart == -1) {
			return null;
		}

		String packageName = classQualifiedName.substring(0, classStart);
		if (packageName.isEmpty()) {
			return null;
		}

		// 'className' includes a leading '.'
		String className = classQualifiedName.substring(classStart);

		String outputName = replacePackage(packageName);
		if (outputName == null) {
			return null;
		}

		if (inputPrefix == null) {
			return outputName + className;
		} else {
			return inputPrefix + outputName + className;
		}
	}
}
