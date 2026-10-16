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

import static org.eclipse.transformer.util.SignatureUtils.classNameToResourceName;

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import aQute.bnd.classfile.AnnotationDefaultAttribute;
import aQute.bnd.classfile.AnnotationInfo;
import aQute.bnd.classfile.AnnotationsAttribute;
import aQute.bnd.classfile.Attribute;
import aQute.bnd.classfile.ClassFile;
import aQute.bnd.classfile.CodeAttribute;
import aQute.bnd.classfile.CodeAttribute.ExceptionHandler;
import aQute.bnd.classfile.ConstantPool;
import aQute.bnd.classfile.ConstantPool.ClassInfo;
import aQute.bnd.classfile.ConstantPool.MethodTypeInfo;
import aQute.bnd.classfile.ConstantPool.NameAndTypeInfo;
import aQute.bnd.classfile.ConstantPool.StringInfo;
import aQute.bnd.classfile.ConstantValueAttribute;
import aQute.bnd.classfile.ElementValueInfo;
import aQute.bnd.classfile.ElementValueInfo.EnumConst;
import aQute.bnd.classfile.ElementValueInfo.ResultConst;
import aQute.bnd.classfile.EnclosingMethodAttribute;
import aQute.bnd.classfile.ExceptionsAttribute;
import aQute.bnd.classfile.FieldInfo;
import aQute.bnd.classfile.InnerClassesAttribute;
import aQute.bnd.classfile.InnerClassesAttribute.InnerClass;
import aQute.bnd.classfile.LocalVariableTableAttribute;
import aQute.bnd.classfile.LocalVariableTableAttribute.LocalVariable;
import aQute.bnd.classfile.LocalVariableTypeTableAttribute;
import aQute.bnd.classfile.LocalVariableTypeTableAttribute.LocalVariableType;
import aQute.bnd.classfile.MemberInfo;
import aQute.bnd.classfile.MethodInfo;
import aQute.bnd.classfile.ModuleAttribute;
import aQute.bnd.classfile.ModuleAttribute.Export;
import aQute.bnd.classfile.ModuleAttribute.Open;
import aQute.bnd.classfile.ModuleAttribute.Provide;
import aQute.bnd.classfile.ModuleMainClassAttribute;
import aQute.bnd.classfile.ModulePackagesAttribute;
import aQute.bnd.classfile.NestHostAttribute;
import aQute.bnd.classfile.NestMembersAttribute;
import aQute.bnd.classfile.ParameterAnnotationInfo;
import aQute.bnd.classfile.ParameterAnnotationsAttribute;
import aQute.bnd.classfile.RecordAttribute;
import aQute.bnd.classfile.RecordAttribute.RecordComponent;
import aQute.bnd.classfile.RuntimeInvisibleAnnotationsAttribute;
import aQute.bnd.classfile.RuntimeInvisibleParameterAnnotationsAttribute;
import aQute.bnd.classfile.RuntimeInvisibleTypeAnnotationsAttribute;
import aQute.bnd.classfile.RuntimeVisibleAnnotationsAttribute;
import aQute.bnd.classfile.RuntimeVisibleParameterAnnotationsAttribute;
import aQute.bnd.classfile.RuntimeVisibleTypeAnnotationsAttribute;
import aQute.bnd.classfile.SignatureAttribute;
import aQute.bnd.classfile.StackMapTableAttribute;
import aQute.bnd.classfile.StackMapTableAttribute.AppendFrame;
import aQute.bnd.classfile.StackMapTableAttribute.FullFrame;
import aQute.bnd.classfile.StackMapTableAttribute.ObjectVariableInfo;
import aQute.bnd.classfile.StackMapTableAttribute.SameLocals1StackItemFrame;
import aQute.bnd.classfile.StackMapTableAttribute.SameLocals1StackItemFrameExtended;
import aQute.bnd.classfile.StackMapTableAttribute.StackMapFrame;
import aQute.bnd.classfile.StackMapTableAttribute.VerificationTypeInfo;
import aQute.bnd.classfile.TypeAnnotationInfo;
import aQute.bnd.classfile.TypeAnnotationsAttribute;
import aQute.bnd.classfile.builder.ClassFileBuilder;
import aQute.bnd.classfile.builder.MutableConstantPool;
import aQute.lib.io.ByteBufferDataInput;
import aQute.lib.io.ByteBufferDataOutput;
import org.eclipse.transformer.TransformException;
import org.eclipse.transformer.action.ActionContext;
import org.eclipse.transformer.action.ActionType;
import org.eclipse.transformer.action.ByteData;
import org.eclipse.transformer.action.SignatureRule;
import org.eclipse.transformer.action.SignatureRule.SignatureType;
import org.eclipse.transformer.util.FileUtils;
import org.slf4j.Logger;

/**
 * Transform class bytes.
 * <p>
 * The main updates are to replace package references, for example, to replace
 * "javax.servlet" with "jakarta.servlet". Package updates are made using whole,
 * fully qualified package names. Where the package name is embedded in a
 * complex binary data structure (for example, within a signature or within a
 * descriptor), the data structure is unpacked before attempting to replace the
 * package.
 * <p>
 * Package references can have two formats, first, a "dotted" format, for
 * example, "javax.servlet", and second, a "slashed" format, for example,
 * "javax/servlet". Package references in either format are replaced.
 * <p>
 * Unless specified with a wildcard, a package replacement matches only entire
 * qualified package names. Then, a rule which specifies that "javax.servlet" is
 * to be replaced with "jakarta.servlet" will update the class reference
 * "java.servlet.Servlet" to "jakarta.servlet.Servlet". The rule <em>will
 * not</em> update the package reference "javax.servlet.util".
 * <p>
 * However, if the rule is specified with a wildcard: "javax.servlet.*" is to be
 * replaced with "jakarta.servlet.*", then the package reference
 * "javax.servlet.util" will be updated.
 * <p>
 * Package matching is careful to avoid accidental partial matches. For example,
 * "javax.servlet" will not match "head.javax.servlet", "prefix_javax.servlet",
 * "javax.servlet_suffix", or "javax.servlet.tail".
 * <p>
 * Package renaming is performed not just on explicit package references, which
 * appear in binary signatures and binary type references at specific locations.
 * Package renaming is also performed on string constant values.
 * </p>
 * Note that package renaming is also performed on Java and JSP resources, and
 * that package renaming will cause changes to the names of resources: For
 * example, using the rule that "javax.servlet" is to be replaced with
 * "jakarta.servlet", a resource in the folder "javax/servlet" <em>will</em> be
 * moved to the folder "jakarta/servlet".
 * <p>
 * In addition to package renaming, the class action performed direct string
 * constant updates. These are performed either as global replacements, or as
 * class specific updates.
 * <p>
 * Global and per-class updates are also performed on java resources. Global
 * updates are performed on JSP resources.
 */
public class ClassActionImpl extends ElementActionImpl {
	/**
	 * Adjust an input path according to the changes made to the name of the
	 * class stored at that path. The input path is expected to match the input
	 * class name using usual java class resource placement. If the input path
	 * does not match the input class name, the class is incorrectly
	 * placed. Find an approximate location for the class based on whether the
	 * input path begins with "WEB-INF/classes/" or "META-INF/versions/".
	 * Otherwise, use the usual path for the output class name using java class
	 * resource placement.
	 *
	 * @param inputPath The initial path to the class.
	 * @param inputClassName The initial class name.
	 * @param outputClassName The final class name.
	 * @return An output path for the class, placing the output class name in
	 *         the same relationship to the output path as the input class name
	 *         has relative to the input path.
	 */
	public String relocateClass(String inputPath, String inputClassName, String outputClassName) {
		String resourceInputPath = classNameToResourceName(inputClassName);
		String resourceOutputPath = classNameToResourceName(outputClassName);

		// Expected case: The class was located at the usual relative location,

		if (resourceInputPath.equals(inputPath)) {
			return resourceOutputPath;
		}

		// Expected case: Class was located under a sub-path.
		// This covers "WEB-INF/classes/" and "META-INF/versions/" --
		// when input class name matches the input path.

		int length = inputPath.length();
		int tail = resourceInputPath.length();
		int head = length - tail;
		if ( (length > tail) &&
			 (inputPath.charAt(head - 1) == '/') &&
		     inputPath.regionMatches(head, resourceInputPath, 0, tail) ) {
			return inputPath.substring(0, head).concat(resourceOutputPath);
		}

		// Unexpected cases: The class was not properly named. Do our best to
		// place the class in the same relative location.

		String outputPath;
		String prefix;
		if (inputPath.startsWith("WEB-INF/classes/")) {
			prefix = "WEB-INF/classes/";
		} else if (inputPath.startsWith("META-INF/versions/")) {
			int nextSlash = inputPath.indexOf('/', "META-INF/versions/".length());
			if (nextSlash == -1) {
				prefix = "META-INF/versions/";
			} else {
				prefix = inputPath.substring(0, nextSlash + 1);
			}
		} else {
			prefix = null;
		}

		if ( prefix == null ) {
			outputPath = resourceOutputPath;
		} else {
			outputPath = prefix.concat(resourceOutputPath);
		}

		getLogger().warn( "Class location mismatch:" +
		      " Class location [ {} ] does not match class name [ {} ]." +
			  " Corrected transformed location to [ {} ] using transformed name [ {} ].",
			  inputPath, inputClassName,
			  outputPath, outputClassName);

		return outputPath;
	}

	//

	private static final int DUMP_WIDTH = 16;

	private void traceDump(ByteData inputData) {
		Logger useLogger = getLogger();
		if (!useLogger.isTraceEnabled()) {
			return;
		}

		StringBuilder outputBuilder = new StringBuilder();

		ByteBuffer buffer = inputData.buffer();
		while (buffer.hasRemaining()) {
			int nextWidth = Math.min(buffer.remaining(), DUMP_WIDTH);
			String nextLine = traceDumpLine(outputBuilder, buffer, nextWidth);
			useLogger.trace(nextLine);
		}
	}

	private String traceDumpLine(StringBuilder builder, ByteBuffer buffer, int width) {
		for (int byteNo = 0; byteNo < width; byteNo++) {
			byte nextByte = buffer.get();
			builder.append(String.format("%02x ", nextByte));
		}
		String line = builder.toString();
		builder.setLength(0);
		return line;
	}

	public ClassActionImpl(ActionContext context) {
		super(context);

		List<StringReplacement> useReplacements = createActiveReplacements(context.signatureRule());

		this.activeReplacements = useReplacements.isEmpty() ? NO_ACTIVE_REPLACEMENTS : useReplacements;
	}

	protected List<StringReplacement> createActiveReplacements(SignatureRule signatureRule) {
		List<StringReplacement> replacements = new ArrayList<>();

		if ( !signatureRule.getDirectPerClassUpdates().isEmpty() ) {
			replacements.add(this::directPerClassUpdate);
		}
		if ( !signatureRule.getDirectGlobalUpdates().isEmpty() ) {
			replacements.add(this::directGlobalUpdate);
		}

		if ( !signatureRule.getPackageRenames().isEmpty() )
			replacements.add(this::descriptorUpdate);{
			replacements.add(this::packagesUpdate);
			replacements.add(this::binaryTypeUpdate);
			replacements.add(this::binaryPackagesUpdate);
		}

		return replacements;
	}

	private final List<StringReplacement> activeReplacements;

	@Override
	protected List<StringReplacement> getActiveReplacements() {
		return activeReplacements;
	}

	/**
	 * Control API: Subclasses should override to control whether they want to
	 * continue applying updates, or stop after the first update which had a
	 * non-trivial or null effect.
	 * <p>
	 * Override: Class actions currently perform at most one update. This
	 * override is used to make the implementation consistent with the
	 * implementation before the use of active replacements.
	 * <p>
	 * See: {@link ActionImpl#updateString(String, String, String, List)}.
	 *
	 * @return True or false telling if multiple updates are allowed. This
	 *         implementation answers false.
	 */
	@Override
	protected boolean allowMultipleReplacements() {
		return false;
	}

	//

	@Override
	public ActionType getActionType() {
		return ActionType.CLASS;
	}

	//

	@Override
	public ClassChangesImpl newChanges() {
		return new ClassChangesImpl();
	}

	@Override
	public ClassChangesImpl getActiveChanges() {
		return (ClassChangesImpl) super.getActiveChanges();
	}

	@Override
	public ClassChangesImpl getLastActiveChanges() {
		return (ClassChangesImpl) super.getLastActiveChanges();
	}

	protected void setClassNames(String inputClassName, String outputClassName) {
		ClassChangesImpl useChanges = getActiveChanges();
		useChanges.setInputClassName(inputClassName);
		useChanges.setOutputClassName(outputClassName);
	}

	protected void setSuperClassNames(String inputSuperName, String outputSuperName) {
		ClassChangesImpl useChanges = getActiveChanges();
		useChanges.setInputSuperName(inputSuperName);
		useChanges.setOutputSuperName(outputSuperName);
	}

	protected void addModifiedInterface() {
		getActiveChanges().addModifiedInterface();
	}

	protected void addModifiedField() {
		getActiveChanges().addModifiedField();
	}

	protected void addModifiedMethod() {
		getActiveChanges().addModifiedMethod();
	}

	protected void addModifiedAttribute() {
		getActiveChanges().addModifiedAttribute();
	}

	protected void setModifiedConstants(int modifiedConstants) {
		getActiveChanges().addModifiedConstants(modifiedConstants);
	}

	//

	@Override
	public ByteData apply(ByteData inputData) throws TransformException {
		Logger useLogger = getLogger();

		startRecording(inputData);
		try {
			useLogger.debug("Class input: [ {} ]", inputData);
			traceDump(inputData);

			ClassFile inputClass;
			try {
				DataInput inputClassData = ByteBufferDataInput.wrap(inputData.buffer());
				inputClass = ClassFile.parseClassFile(inputClassData);
			} catch (IOException e) {
				throw new TransformException("Failed to parse raw class bytes [ " + inputData.name() + " ]", e);
			}

			displayClass(inputData.name(), inputClass);

			ClassFileBuilder classBuilder = new ClassFileBuilder(inputClass);

			// Transform the class declaration ...

			String inputClassName = classBuilder.this_class();
			String outputClassName = transformBinaryType(inputClassName);

			String outputName;
			if (outputClassName != null) {
				classBuilder.this_class(outputClassName);
				outputName = relocateClass(inputData.name(), inputClassName, outputClassName);
				useLogger.debug("Class name [ {} ] -> [ {} ]", inputData.name(), outputName);
			} else {
				outputClassName = inputClassName;
				outputName = inputData.name();
			}

			setClassNames(inputClassName, outputClassName);
			setResourceNames(inputData.name(), outputName);

			useLogger.trace("{}", classBuilder);

			String inputSuperName = classBuilder.super_class();
			if (inputSuperName != null) {
				String outputSuperName = transformBinaryType(inputSuperName);
				if (outputSuperName != null) {
					classBuilder.super_class(outputSuperName);
				} else {
					outputSuperName = inputSuperName;
				}

				setSuperClassNames(inputSuperName, outputSuperName);

				if (!outputSuperName.equals("java/lang/Object")) {
					useLogger.trace("  extends {}", outputSuperName);
				}
			}

			List<String> interfaces = classBuilder.interfaces();
			if (!interfaces.isEmpty()) {
				ListIterator<String> interfaceNames = interfaces.listIterator();
				while (interfaceNames.hasNext()) {
					String inputInterfaceName = interfaceNames.next();
					String outputInterfaceName = transformBinaryType(inputInterfaceName);
					if (outputInterfaceName != null) {
						interfaceNames.set(outputInterfaceName);
						addModifiedInterface();
						useLogger.debug("Interface {} -> {}", inputInterfaceName, outputInterfaceName);
					}
				}
				useLogger.trace("  implements {}", interfaces);
			}

			// Transform members ...

			ListIterator<FieldInfo> fields = classBuilder.fields()
				.listIterator();
			while (fields.hasNext()) {
				FieldInfo inputField = fields.next();
				FieldInfo outputField = transform(inputField, FieldInfo::new, SignatureType.FIELD, inputData.name());
				if (outputField != null) {
					fields.set(outputField);
					addModifiedField();
					useLogger.debug("Field  {} -> {}", inputField, outputField);
				}
			}

			ListIterator<MethodInfo> methods = classBuilder.methods()
				.listIterator();
			while (methods.hasNext()) {
				MethodInfo inputMethod = methods.next();
				MethodInfo outputMethod = transform(inputMethod, MethodInfo::new, SignatureType.METHOD,
					inputData.name());
				if (outputMethod != null) {
					methods.set(outputMethod);
					addModifiedMethod();
					useLogger.debug("Method {} -> {}", inputMethod, outputMethod);
				}
			}

			// Transform attributes ...

			ListIterator<Attribute> attributes = classBuilder.attributes()
				.listIterator();
			while (attributes.hasNext()) {
				Attribute inputAttribute = attributes.next();
				Attribute outputAttribute = transform(inputAttribute, SignatureType.CLASS, inputData.name());
				if (outputAttribute != null) {
					attributes.set(outputAttribute);
					addModifiedAttribute();
					useLogger.debug("Attribute {} -> {}", inputAttribute, outputAttribute);
				}
			}

			MutableConstantPool constants = classBuilder.constant_pool();
			useLogger.debug("  Constant pool: {}", constants.size());

			int modifiedConstants = transform(constants, inputData.name());
			if (modifiedConstants > 0) {
				setModifiedConstants(modifiedConstants);
			}

			if (!isChanged()) {
				return inputData;
			}

			if (!isContentChanged()) {
				ByteData outputData = inputData.copy(outputName);
				useLogger.debug("  Class bytes: [ {} ]", outputData);
				return outputData;
			}

			ClassFile outputClass = classBuilder.build();

			Charset charset = inputData.charset();
			ByteBufferDataOutput outputClassData = new ByteBufferDataOutput(inputData.length() + FileUtils.PAGE_SIZE);
			try {
				outputClass.write(outputClassData);
			} catch (IOException e) {
				throw new TransformException("Failed to write transformed class bytes", e);
			}

			ByteData outputData = new ByteDataImpl(outputName, outputClassData.toByteBuffer(), charset);
			useLogger.debug("  Class output: [ {} ]", outputData);
			return outputData;
		} finally {
			stopRecording(inputData);
		}
	}

	private void displayClass(String inputName, ClassFile inputClass) {
		Logger useLogger = getLogger();

		useLogger.debug("Class [ {} ] as [ {} ] ", inputName, inputClass.this_class);
		if (!useLogger.isTraceEnabled()) {
			return;
		}
		useLogger.trace("  Super [ {} ]", inputClass.super_class);
		if (inputClass.interfaces != null) {
			useLogger.trace("  Interfaces [ {} ]", inputClass.interfaces.length);
			for (String interfaceName : inputClass.interfaces) {
				useLogger.trace("    [ {} ]", interfaceName);
			}
		}
		if (inputClass.fields != null) {
			useLogger.trace("  Fields [ {} ]", inputClass.fields.length);
			for (FieldInfo field : inputClass.fields) {
				useLogger.trace("    [ {} ] [ {} ]", field.name, field.descriptor);
			}
		}
		if (inputClass.methods != null) {
			useLogger.trace("  Methods [ {} ]", inputClass.methods.length);
			for (MethodInfo method : inputClass.methods) {
				useLogger.trace("    [ {} ] [ {} ]", method.name, method.descriptor);
			}
		}
	}

	//

	private <MEMBERINFO extends MemberInfo> MEMBERINFO transform(MEMBERINFO member,
		MemberInfo.Constructor<MEMBERINFO> constructor, SignatureType signatureType, String inputName) {

		String inputDescriptor = member.descriptor;
		String outputDescriptor = transformDescriptor(inputDescriptor);
		if (outputDescriptor != null) {
			getLogger().debug("Member {}.{} > {}", member.name, member.descriptor, outputDescriptor);
		}

		Attribute[] inputAttributes = member.attributes;
		Attribute[] outputAttributes = transform(inputAttributes, signatureType, inputName);

		if ((outputDescriptor == null) && (outputAttributes == null)) {
			return null;
		}

		return constructor.init(member.access, member.name,
			((outputDescriptor == null) ? inputDescriptor : outputDescriptor),
			((outputAttributes == null) ? inputAttributes : outputAttributes));
	}

	private Attribute[] transform(Attribute[] inputAttributes, SignatureType signatureType, String inputName) {
		Logger useLogger = getLogger();

		Attribute[] outputAttributes = null;

		for (int attributeNo = 0; attributeNo < inputAttributes.length; attributeNo++) {
			Attribute inputAttribute = inputAttributes[attributeNo];
			Attribute outputAttribute = transform(inputAttribute, signatureType, inputName);
			if (outputAttribute != null) {
				if (outputAttributes == null) {
					outputAttributes = inputAttributes.clone();
				}
				outputAttributes[attributeNo] = outputAttribute;

				useLogger.debug("Attribute [ {} ] [ {} ] -> [ {} ]", attributeNo, inputAttribute, outputAttribute);
			}
		}

		return outputAttributes;
	}

	private Attribute transform(Attribute attr, SignatureType signatureType, String inputName) {
		Logger useLogger = getLogger();

		switch (attr.name()) {
			case SignatureAttribute.NAME : {
				SignatureAttribute inputAttribute = (SignatureAttribute) attr;
				String outputSignature = transformSignature(inputAttribute.signature, signatureType);
				return ((outputSignature == null) ? null : new SignatureAttribute(outputSignature));
			}

			case ExceptionsAttribute.NAME : {
				ExceptionsAttribute inputAttribute = (ExceptionsAttribute) attr;
				String[] inputExceptions = inputAttribute.exceptions;
				String[] outputExceptions = null;
				for (int exNo = 0; exNo < inputExceptions.length; exNo++) {
					String exception = transformBinaryType(inputExceptions[exNo]);
					if (exception != null) {
						if (outputExceptions == null) {
							outputExceptions = inputExceptions.clone();
						}
						outputExceptions[exNo] = exception;
					}
				}

				return ((outputExceptions == null) ? null : new ExceptionsAttribute(outputExceptions));
			}

			case CodeAttribute.NAME : {
				CodeAttribute attribute = (CodeAttribute) attr;

				ExceptionHandler[] inputHandlers = attribute.exception_table;
				ExceptionHandler[] outputHandlers = null;

				for (int handlerNo = 0; handlerNo < inputHandlers.length; handlerNo++) {
					ExceptionHandler inputHandler = inputHandlers[handlerNo];
					String inputCatchType = inputHandler.catch_type;
					if (inputCatchType != null) {
						String outputCatchType = transformBinaryType(inputCatchType);
						if (outputCatchType != null) {
							if (outputHandlers == null) {
								outputHandlers = inputHandlers.clone();
							}
							outputHandlers[handlerNo] = new ExceptionHandler(inputHandler.start_pc, inputHandler.end_pc,
								inputHandler.handler_pc, outputCatchType);
						}
					}
				}

				// TODO Maybe intercept Class.forName/etc calls at
				// runtime to rename types
				//
				// See issue: #99

				Attribute[] inputAttributes = attribute.attributes;
				Attribute[] outputAttributes = transform(inputAttributes, SignatureType.METHOD, inputName);

				if ((outputHandlers == null) && (outputAttributes == null)) {
					return null;
				} else {
					return new CodeAttribute(attribute.max_stack, attribute.max_locals, attribute.code,
						((outputHandlers == null) ? inputHandlers : outputHandlers),
						((outputAttributes == null) ? inputAttributes : outputAttributes));
				}
			}

			case EnclosingMethodAttribute.NAME : {
				EnclosingMethodAttribute attribute = (EnclosingMethodAttribute) attr;

				String methodName = attribute.method_name;

				String inputClassName = attribute.class_name; // Never null
				String outputClassName = transformBinaryType(inputClassName);

				String inputDescriptor = attribute.method_descriptor;
				String outputDescriptor = ((inputDescriptor == null) ? null : transformDescriptor(inputDescriptor));

				if ((outputClassName == null) && (outputDescriptor == null)) {
					useLogger.trace("Enclosing method [ {} ] Class [ {} ] Descriptor [ {} ]", methodName,
						inputClassName, inputDescriptor);
					return null;
				}

				if (outputClassName != null) {
					useLogger.debug("Enclosing method [ {} ] Class [ {} ] -> [ {} ]", methodName, inputClassName,
						outputClassName);
				} else {
					outputClassName = inputClassName;
				}

				if (outputDescriptor != null) {
					useLogger.debug("Enclosing method [ {} ] Descriptor [ {} ] -> [ {} ]", methodName,
						inputDescriptor, outputDescriptor);
				} else {
					outputDescriptor = inputDescriptor;
				}

				return new EnclosingMethodAttribute(outputClassName, methodName, outputDescriptor);
			}

			case StackMapTableAttribute.NAME : {
				StackMapTableAttribute inputAttribute = (StackMapTableAttribute) attr;

				StackMapFrame[] inputFrames = inputAttribute.entries;
				StackMapFrame[] outputFrames = null;

				for (int frameNo = 0; frameNo < inputFrames.length; frameNo++) {
					StackMapFrame inputFrame = inputFrames[frameNo];
					switch (inputFrame.type()) {
						case StackMapFrame.SAME_LOCALS_1_STACK_ITEM : {
							SameLocals1StackItemFrame frame = (SameLocals1StackItemFrame) inputFrame;
							VerificationTypeInfo stack = transform(frame.stack);
							if (stack != null) {
								if (outputFrames == null) {
									outputFrames = inputFrames.clone();
								}
								outputFrames[frameNo] = new SameLocals1StackItemFrame(frame.tag, stack);
							}
							break;
						}
						case StackMapFrame.SAME_LOCALS_1_STACK_ITEM_EXTENDED : {
							SameLocals1StackItemFrameExtended frame = (SameLocals1StackItemFrameExtended) inputFrame;
							VerificationTypeInfo stack = transform(frame.stack);
							if (stack != null) {
								if (outputFrames == null) {
									outputFrames = inputFrames.clone();
								}
								outputFrames[frameNo] = new SameLocals1StackItemFrameExtended(frame.tag, frame.delta,
									stack);
							}
							break;
						}
						case StackMapFrame.APPEND : {
							AppendFrame frame = (AppendFrame) inputFrame;
							VerificationTypeInfo[] locals = transform(frame.locals);
							if (locals != null) {
								if (outputFrames == null) {
									outputFrames = inputFrames.clone();
								}
								outputFrames[frameNo] = new AppendFrame(frame.tag, frame.delta, locals);
							}
							break;
						}
						case StackMapFrame.FULL_FRAME : {
							FullFrame frame = (FullFrame) inputFrame;
							VerificationTypeInfo[] locals = transform(frame.locals);
							VerificationTypeInfo[] stack = transform(frame.stack);
							if ((locals != null) || (stack != null)) {
								if (outputFrames == null) {
									outputFrames = inputFrames.clone();
								}
								outputFrames[frameNo] = new FullFrame(frame.tag, frame.delta,
									((locals == null) ? frame.locals : locals),
									((stack == null) ? frame.stack : stack));
							}
							break;
						}
						default :
							break;
					}
				}
				if (outputFrames == null) {
					return null;
				} else {
					return new StackMapTableAttribute(outputFrames);
				}
			}

			case InnerClassesAttribute.NAME : {
				InnerClassesAttribute inputAttribute = (InnerClassesAttribute) attr;

				InnerClass[] inputClasses = inputAttribute.classes;
				InnerClass[] outputClasses = null;

				for (int classNo = 0; classNo < inputClasses.length; classNo++) {
					InnerClass inputClass = inputClasses[classNo];

					String inputInnerClass = inputClass.inner_class;
					String outputInnerClass = ((inputInnerClass == null) ? null : transformBinaryType(inputInnerClass));

					String inputOuterClass = inputClass.outer_class;
					String outputOuterClass = ((inputOuterClass == null) ? null : transformBinaryType(inputOuterClass));

					if ((outputInnerClass != null) || (outputOuterClass != null)) {
						if (outputInnerClass != null) {
							useLogger.debug("Inner class attribute [ {} ] Inner [ {} ] -> [ {} ]", classNo,
								inputInnerClass, outputInnerClass);
						} else {
							outputInnerClass = inputInnerClass;
						}
						if (outputOuterClass != null) {
							useLogger.debug("Inner class attribute [ {} ] Outer [ {} ] -> [ {} ]", classNo,
								inputOuterClass, outputOuterClass);
						} else {
							outputOuterClass = inputOuterClass;
						}
						if (outputClasses == null) {
							outputClasses = inputClasses.clone();
						}
						outputClasses[classNo] = new InnerClass(outputInnerClass, outputOuterClass,
							inputClass.inner_name, inputClass.inner_access);
					}
				}

				if (outputClasses == null) {
					return null;
				} else {
					return new InnerClassesAttribute(outputClasses);
				}
			}

			case LocalVariableTableAttribute.NAME : {
				LocalVariableTableAttribute inputAttribute = (LocalVariableTableAttribute) attr;

				LocalVariable[] inputVariables = inputAttribute.local_variable_table;
				LocalVariable[] outputVariables = null;

				for (int varNo = 0; varNo < inputVariables.length; varNo++) {
					LocalVariable inputVariable = inputVariables[varNo];
					String outputDescriptor = transformDescriptor(inputVariable.descriptor);
					if (outputDescriptor != null) {
						if (outputVariables == null) {
							outputVariables = inputVariables.clone();
						}
						outputVariables[varNo] = new LocalVariable(inputVariable.start_pc, inputVariable.length,
							inputVariable.name, outputDescriptor, inputVariable.index);
					}
				}

				if (outputVariables == null) {
					return null;
				} else {
					return new LocalVariableTableAttribute(outputVariables);
				}
			}

			case LocalVariableTypeTableAttribute.NAME : {
				LocalVariableTypeTableAttribute inputAttribute = (LocalVariableTypeTableAttribute) attr;

				LocalVariableType[] inputTypes = inputAttribute.local_variable_type_table;
				LocalVariableType[] outputTypes = null;

				for (int varNo = 0; varNo < inputTypes.length; varNo++) {
					LocalVariableType inputType = inputTypes[varNo];
					String outputSignature = transformSignature(inputType.signature, SignatureType.FIELD);

					if (outputSignature != null) {
						if (outputTypes == null) {
							outputTypes = inputTypes.clone();
						}
						outputTypes[varNo] = new LocalVariableType(inputType.start_pc, inputType.length, inputType.name,
							outputSignature, inputType.index);
					}
				}

				if (outputTypes == null) {
					return null;
				} else {
					return new LocalVariableTypeTableAttribute(outputTypes);
				}
			}

			case RuntimeVisibleAnnotationsAttribute.NAME : {
				RuntimeVisibleAnnotationsAttribute inputAttribute = (RuntimeVisibleAnnotationsAttribute) attr;
				RuntimeVisibleAnnotationsAttribute outputAttribute = transform(inputAttribute,
					RuntimeVisibleAnnotationsAttribute::new, inputName);
				return outputAttribute;
			}

			case RuntimeInvisibleAnnotationsAttribute.NAME : {
				RuntimeInvisibleAnnotationsAttribute inputAttribute = (RuntimeInvisibleAnnotationsAttribute) attr;
				RuntimeInvisibleAnnotationsAttribute outputAttribute = transform(inputAttribute,
					RuntimeInvisibleAnnotationsAttribute::new, inputName);
				return outputAttribute;
			}

			case RuntimeVisibleParameterAnnotationsAttribute.NAME : {
				RuntimeVisibleParameterAnnotationsAttribute inputAttribute = (RuntimeVisibleParameterAnnotationsAttribute) attr;
				RuntimeVisibleParameterAnnotationsAttribute outputAttribute = transform(inputAttribute,
					RuntimeVisibleParameterAnnotationsAttribute::new, inputName);
				return outputAttribute;
			}

			case RuntimeInvisibleParameterAnnotationsAttribute.NAME : {
				RuntimeInvisibleParameterAnnotationsAttribute inputAttribute = (RuntimeInvisibleParameterAnnotationsAttribute) attr;
				RuntimeInvisibleParameterAnnotationsAttribute outputAttribute = transform(inputAttribute,
					RuntimeInvisibleParameterAnnotationsAttribute::new, inputName);
				return outputAttribute;
			}

			case RuntimeVisibleTypeAnnotationsAttribute.NAME : {
				RuntimeVisibleTypeAnnotationsAttribute inputAttribute = (RuntimeVisibleTypeAnnotationsAttribute) attr;
				RuntimeVisibleTypeAnnotationsAttribute outputAttribute = transform(inputAttribute,
					RuntimeVisibleTypeAnnotationsAttribute::new, inputName);
				return outputAttribute;
			}

			case RuntimeInvisibleTypeAnnotationsAttribute.NAME : {
				RuntimeInvisibleTypeAnnotationsAttribute inputAttribute = (RuntimeInvisibleTypeAnnotationsAttribute) attr;
				RuntimeInvisibleTypeAnnotationsAttribute outputAttribute = transform(inputAttribute,
					RuntimeInvisibleTypeAnnotationsAttribute::new, inputName);
				return outputAttribute;
			}

			case AnnotationDefaultAttribute.NAME : {
				AnnotationDefaultAttribute inputAttribute = (AnnotationDefaultAttribute) attr;
				Object outputValue = transformElementValue(inputAttribute.value, inputName, "", "");
				return ((outputValue == null) ? null : new AnnotationDefaultAttribute(outputValue));
			}

			case ModuleAttribute.NAME : {
				ModuleAttribute inputAttribute = (ModuleAttribute) attr;
				// transform exports package names
				Export[] inputExports = inputAttribute.exports;
				Export[] outputExports = null;
				for (int i = 0; i < inputExports.length; i++) {
					Export inputExport = inputExports[i];
					String outputExport = replaceBinaryPackage(inputExport.exports);
					if (outputExport != null) {
						if (outputExports == null) {
							outputExports = inputExports.clone();
						}
						outputExports[i] = new Export(outputExport, inputExport.exports_flags, inputExport.exports_to);
					}
				}
				// transform opens package names
				Open[] inputOpens = inputAttribute.opens;
				Open[] outputOpens = null;
				for (int i = 0; i < inputOpens.length; i++) {
					Open inputOpen = inputOpens[i];
					String outputOpen = replaceBinaryPackage(inputOpen.opens);
					if (outputOpen != null) {
						if (outputOpens == null) {
							outputOpens = inputOpens.clone();
						}
						outputOpens[i] = new Open(outputOpen, inputOpen.opens_flags, inputOpen.opens_to);
					}
				}
				// transform uses class names
				String[] inputUses = inputAttribute.uses;
				String[] outputUses = null;
				for (int i = 0; i < inputUses.length; i++) {
					String outputUse = transformBinaryType(inputUses[i]);
					if (outputUse != null) {
						if (outputUses == null) {
							outputUses = inputUses.clone();
						}
						outputUses[i] = outputUse;
					}
				}
				// transform provides class names
				Provide[] inputProvides = inputAttribute.provides;
				Provide[] outputProvides = null;
				for (int i = 0; i < inputProvides.length; i++) {
					Provide inputProvide = inputProvides[i];
					String outputProvide = transformBinaryType(inputProvide.provides);
					String[] inputProvideWiths = inputProvide.provides_with;
					String[] outputProvideWiths = null;
					for (int j = 0; j < inputProvideWiths.length; j++) {
						String outputProvideWith = transformBinaryType(inputProvideWiths[j]);
						if (outputProvideWith != null) {
							if (outputProvideWiths == null) {
								outputProvideWiths = inputProvideWiths.clone();
							}
							outputProvideWiths[j] = outputProvideWith;
						}

					}
					if ((outputProvide != null) || (outputProvideWiths != null)) {
						if (outputProvide == null) {
							outputProvide = inputProvide.provides;
						}
						if (outputProvideWiths == null) {
							outputProvideWiths = inputProvideWiths;
						}
						if (outputProvides == null) {
							outputProvides = inputProvides.clone();
						}
						outputProvides[i] = new Provide(outputProvide, outputProvideWiths);
					}
				}

				if ((outputExports == null) && (outputOpens == null) && (outputUses == null)
					&& (outputProvides == null)) {
					return null;
				}
				if (outputExports == null) {
					outputExports = inputExports;
				}
				if (outputOpens == null) {
					outputOpens = inputOpens;
				}
				if (outputUses == null) {
					outputUses = inputUses;
				}
				if (outputProvides == null) {
					outputProvides = inputProvides;
				}
				return new ModuleAttribute(inputAttribute.module_name, inputAttribute.module_flags,
					inputAttribute.module_version, inputAttribute.requires, outputExports, outputOpens, outputUses,
					outputProvides);
			}

			case ModuleMainClassAttribute.NAME : {
				ModuleMainClassAttribute inputAttribute = (ModuleMainClassAttribute) attr;
				String inputMainClass = inputAttribute.main_class;
				String outputMainClass = transformBinaryType(inputMainClass);
				return (outputMainClass == null) ? null : new ModuleMainClassAttribute(outputMainClass);
			}

			case ModulePackagesAttribute.NAME : {
				ModulePackagesAttribute inputAttribute = (ModulePackagesAttribute) attr;
				String[] inputPackages = inputAttribute.packages;
				String[] outputPackages = null;

				for (int i = 0; i < inputPackages.length; i++) {
					String outputPackage = replaceBinaryPackage(inputPackages[i]);
					if (outputPackage != null) {
						if (outputPackages == null) {
							outputPackages = inputPackages.clone();
						}
						outputPackages[i] = outputPackage;
					}
				}

				return (outputPackages == null) ? null : new ModulePackagesAttribute(outputPackages);
			}

			case NestHostAttribute.NAME : {
				NestHostAttribute inputAttribute = (NestHostAttribute) attr;
				String inputHostClass = inputAttribute.host_class;
				String outputHostClass = transformBinaryType(inputHostClass);
				return (outputHostClass == null) ? null : new NestHostAttribute(outputHostClass);
			}

			case NestMembersAttribute.NAME : {
				NestMembersAttribute inputAttribute = (NestMembersAttribute) attr;
				String[] inputClasses = inputAttribute.classes;
				String[] outputClasses = null;

				for (int i = 0; i < inputClasses.length; i++) {
					String outputClass = transformBinaryType(inputClasses[i]);
					if (outputClass != null) {
						if (outputClasses == null) {
							outputClasses = inputClasses.clone();
						}
						outputClasses[i] = outputClass;
					}
				}

				return (outputClasses == null) ? null : new NestMembersAttribute(outputClasses);
			}

			case RecordAttribute.NAME : {
				RecordAttribute inputAttribute = (RecordAttribute) attr;
				RecordComponent[] inputComponents = inputAttribute.components;
				RecordComponent[] outputComponents = null;

				for (int i = 0; i < inputComponents.length; i++) {
					RecordComponent inputComponent = inputComponents[i];
					String outputDescriptor = transformDescriptor(inputComponent.descriptor);
					Attribute[] outputAttributes = transform(inputComponent.attributes, SignatureType.FIELD, inputName);
					if ((outputDescriptor != null) || (outputAttributes != null)) {
						if (outputComponents == null) {
							outputComponents = inputComponents.clone();
						}
						outputComponents[i] = new RecordComponent(inputComponent.name,
							(outputDescriptor == null) ? inputComponent.descriptor : outputDescriptor,
							(outputAttributes == null) ? inputComponent.attributes : outputAttributes);
					}
				}

				return (outputComponents == null) ? null : new RecordAttribute(outputComponents);
			}

			case ConstantValueAttribute.NAME : {
				ConstantValueAttribute inputAttribute = (ConstantValueAttribute) attr;
				Object inputValue = inputAttribute.value;
				Object outputValue = transformConstantValue(inputValue, inputName);
				return ((outputValue == null) ? null : new ConstantValueAttribute(outputValue));
			}

			default :
				break;
		}

		return null;
	}

	private Object transformConstantValue(Object inputValue, String inputName) {
		if (inputValue instanceof String) {
			return transformString(inputName, "ConstantValue", (String) inputValue);
		} else {
			getLogger().trace("    Non-String ConstantValue: {} (unchanged)", inputValue);
			return null;
		}
	}

	private String transformString(String inputName, String inputCase, String initialValue) {
		return updateString(inputName, inputCase, initialValue);
	}

	private <ANNOTATIONSATTRIBUTE extends AnnotationsAttribute> ANNOTATIONSATTRIBUTE transform(
		ANNOTATIONSATTRIBUTE inputAttribute, AnnotationsAttribute.Constructor<ANNOTATIONSATTRIBUTE> constructor, String inputName) {

		AnnotationInfo[] outputAnnotations = transform(inputAttribute.annotations, inputName);

		return ((outputAnnotations == null) ? null : constructor.init(outputAnnotations));
	}

	private AnnotationInfo[] transform(AnnotationInfo[] inputAnnotations, String inputName) {
		AnnotationInfo[] outputAnnotations = null;

		for (int annoNo = 0; annoNo < inputAnnotations.length; annoNo++) {
			AnnotationInfo inputAnnotation = inputAnnotations[annoNo];
			AnnotationInfo outputAnnotation = transform(inputAnnotation, AnnotationInfo::new, inputName);
			if (outputAnnotation != null) {
				if (outputAnnotations == null) {
					outputAnnotations = inputAnnotations.clone();
				}
				outputAnnotations[annoNo] = outputAnnotation;
			}
		}

		return outputAnnotations;
	}

	private <PARAMETERANNOTATIONSATTRIBUTE extends ParameterAnnotationsAttribute> PARAMETERANNOTATIONSATTRIBUTE transform(
		PARAMETERANNOTATIONSATTRIBUTE attribute,
		ParameterAnnotationsAttribute.Constructor<PARAMETERANNOTATIONSATTRIBUTE> constructor, String inputName) {

		ParameterAnnotationInfo[] outputParmAnnotations = transform(attribute.parameter_annotations, inputName);
		if (outputParmAnnotations == null) {
			return null;
		} else {
			return constructor.init(outputParmAnnotations);
		}
	}

	private ParameterAnnotationInfo[] transform(ParameterAnnotationInfo[] inputParmAnnotations, String inputName) {
		ParameterAnnotationInfo[] outputParmAnnotations = null;

		for (int parmNo = 0; parmNo < inputParmAnnotations.length; parmNo++) {
			ParameterAnnotationInfo inputParmAnnotation = inputParmAnnotations[parmNo];
			AnnotationInfo[] outputAnnotations = transform(inputParmAnnotation.annotations, inputName);
			if (outputAnnotations != null) {
				if (outputParmAnnotations == null) {
					outputParmAnnotations = inputParmAnnotations.clone();
				}
				outputParmAnnotations[parmNo] = new ParameterAnnotationInfo(inputParmAnnotation.parameter,
					outputAnnotations);
			}
		}

		return outputParmAnnotations;
	}

	private <TYPEANNOTATIONSATTRIBUTE extends TypeAnnotationsAttribute> TYPEANNOTATIONSATTRIBUTE transform(
		TYPEANNOTATIONSATTRIBUTE inputAttribute,
		TypeAnnotationsAttribute.Constructor<TYPEANNOTATIONSATTRIBUTE> constructor, String inputName) {

		TypeAnnotationInfo[] outputAnnotations = transform(inputAttribute.type_annotations, inputName);
		if (outputAnnotations == null) {
			return null;
		} else {
			return constructor.init(outputAnnotations);
		}
	}

	private TypeAnnotationInfo[] transform(TypeAnnotationInfo[] inputAnnotations, String inputName) {
		TypeAnnotationInfo[] outputAnnotations = null;

		for (int annoNo = 0; annoNo < inputAnnotations.length; annoNo++) {
			TypeAnnotationInfo inputAnnotation = inputAnnotations[annoNo];
			TypeAnnotationInfo outputAnnotation = transform(inputAnnotation,
				(type, values) -> new TypeAnnotationInfo(inputAnnotation.target_type, inputAnnotation.target_info,
					inputAnnotation.target_index, inputAnnotation.type_path, type, values), inputName);
			if (outputAnnotation != null) {
				if (outputAnnotations == null) {
					outputAnnotations = inputAnnotations.clone();
				}
				outputAnnotations[annoNo] = outputAnnotation;
			}
		}

		return outputAnnotations;
	}

	private <ANNOTATIONINFO extends AnnotationInfo> ANNOTATIONINFO transform(ANNOTATIONINFO inputAnnotation,
		AnnotationInfo.Constructor<ANNOTATIONINFO> constructor, String inputName) {

		String inputType = inputAnnotation.type;
		String outputType = transformDescriptor(inputType);

		ElementValueInfo[] inputValues = inputAnnotation.values;
		ElementValueInfo[] outputValues = transform(inputValues, inputName, inputType);

		if ((outputType == null) && (outputValues == null)) {
			return null;
		} else {
			return constructor.init(((outputType == null) ? inputType : outputType),
				((outputValues == null) ? inputValues : outputValues));
		}
	}

	private ElementValueInfo[] transform(ElementValueInfo[] inputElementValues, String inputName,
		String annotationType) {
		ElementValueInfo[] outputElementValues = null;

		for (int valueNo = 0; valueNo < inputElementValues.length; valueNo++) {
			ElementValueInfo inputElementValue = inputElementValues[valueNo];
			Object outputValue = transformElementValue(inputElementValue.value, inputName, annotationType,
				inputElementValue.name);

			if (outputValue != null) {
				if (outputElementValues == null) {
					outputElementValues = inputElementValues.clone();
				}
				outputElementValues[valueNo] = new ElementValueInfo(inputElementValue.name, outputValue);
			}
		}

		return outputElementValues;
	}

	private Object transformElementValue(Object inputValue, String inputName, String annotationType,
		String elementName) {
		if (inputValue instanceof EnumConst enumValue) {
			String inputType = enumValue.type;
			String outputType = transformDescriptor(inputType);
			if (outputType == null) {
				return null;
			} else {
				return new EnumConst(outputType, enumValue.name);
			}

		} else if (inputValue instanceof ResultConst resultValue) {
			String inputDescriptor = resultValue.descriptor;
			String outputDescriptor = transformDescriptor(inputDescriptor);
			if (outputDescriptor == null) {
				return null;
			} else {
				return new ResultConst(outputDescriptor);
			}

		} else if (inputValue instanceof AnnotationInfo annotationValue) {
			return transform(annotationValue, AnnotationInfo::new, inputName);

		} else if (inputValue instanceof String stringValue) {
			String result = transformString(inputName, "AnnotationValue", stringValue);
			// Replace package version in OSGi Version annotation.
			if (inputName.endsWith("/package-info.class")
				&& annotationType.equals("Lorg/osgi/annotation/versioning/Version;") && elementName.equals("value")) {
				String binaryPackageName = inputName.substring(0, inputName.lastIndexOf('/'));
				String dottedPackageName = binaryPackageName.replace('/', '.');
				String oldVersion = (result == null) ? stringValue : result;
				String replacementVersion = replacePackageVersion("Export-Package", dottedPackageName, oldVersion);
				if (replacementVersion != null) {
					result = replacementVersion;
				}
			}
			return result;
		} else if (inputValue instanceof Object[] inputElementValues) {
			Object[] outputElementValues = null;

			for (int valueNo = 0; valueNo < inputElementValues.length; valueNo++) {
				Object outputElementValue = transformElementValue(inputElementValues[valueNo], inputName,
					annotationType, elementName);
				if (outputElementValue != null) {
					if (outputElementValues == null) {
						outputElementValues = inputElementValues.clone();
					}
					outputElementValues[valueNo] = outputElementValue;
				}
			}

			return outputElementValues;

		} else {
			return null;
		}
	}

	//

	private VerificationTypeInfo[] transform(VerificationTypeInfo[] inputVtis) {
		VerificationTypeInfo[] outputVtis = null;

		for (int vtiNo = 0; vtiNo < inputVtis.length; vtiNo++) {
			VerificationTypeInfo inputVti = inputVtis[vtiNo];
			VerificationTypeInfo outputVti = transform(inputVti);
			if (outputVti != null) {
				if (outputVtis == null) {
					outputVtis = inputVtis.clone();
				}
				outputVtis[vtiNo] = outputVti;
			}
		}

		return outputVtis;
	}

	private VerificationTypeInfo transform(VerificationTypeInfo vti) {
		if (!(vti instanceof ObjectVariableInfo inputOvi)) {
			return null;
		}

		String inputType = inputOvi.type;
		if (inputType == null) {
			return null;
		}

		String outputType = transformBinaryType(inputType);
		if (outputType == null) {
			return null;
		} else {
			return new ObjectVariableInfo(inputOvi.tag, outputType);
		}
	}

	//

	private void logConstant(String typeName, String initialValue, String finalValue, String inputName) {
		getLogger().trace("Class [ {} ] [ {} ] [ {} ] [ {} ]", inputName, typeName, initialValue, finalValue);
	}

	private int transform(MutableConstantPool constants, String inputName) throws TransformException {
		Logger useLogger = getLogger();

		int modifiedConstants = 0;

		int numConstants = constants.size();
		for (int constantNo = 1; constantNo < numConstants; constantNo++) {
			if (useLogger.isTraceEnabled()) {
				//noinspection StringConcatenationArgumentToLogCall
				useLogger
					.trace(String.format("Constant [ %3s ] [ %16s ] [ %s ]", constantNo, constants.tag(constantNo),
					constants.entry(constantNo)));
			}

			switch (constants.tag(constantNo)) {
				case ConstantPool.CONSTANT_Class : {
					ClassInfo info = constants.entry(constantNo);
					String inputClassName = constants.entry(info.class_index);
					String outputClassName = transformBinaryType(inputClassName);
					if (outputClassName != null) {
						constants.entry(constantNo, new ClassInfo(constants.utf8Info(outputClassName)));
						modifiedConstants++;
						useLogger.debug("Class Reference: {} -> {}", inputClassName, outputClassName);
						logConstant("Class Reference", inputClassName, outputClassName, inputName);
					} else {
						useLogger.debug("Skip class {} (unchanged)", inputClassName);
					}
					break;
				}

				case ConstantPool.CONSTANT_NameAndType : {
					NameAndTypeInfo info = constants.entry(constantNo);
					String inputDescriptor = constants.utf8(info.descriptor_index);
					String outputDescriptor = transformDescriptor(inputDescriptor);
					if (outputDescriptor != null) {
						constants.entry(constantNo,
							new NameAndTypeInfo(info.name_index, constants.utf8Info(outputDescriptor)));
						modifiedConstants++;
						useLogger.debug("NameAndType: {} -> {}", inputDescriptor, outputDescriptor);
						logConstant("NameAndType", inputDescriptor, outputDescriptor, inputName);
					} else {
						useLogger.trace("Skip name-and-type {} (unchanged)", inputDescriptor);
					}
					break;
				}

				case ConstantPool.CONSTANT_MethodType : {
					MethodTypeInfo info = constants.entry(constantNo);
					String inputDescriptor = constants.utf8(info.descriptor_index);
					String outputDescriptor = transformDescriptor(inputDescriptor);
					if (outputDescriptor != null) {
						constants.entry(constantNo, new MethodTypeInfo(constants.utf8Info(outputDescriptor)));
						modifiedConstants++;
						useLogger.debug("MethodType: {} -> {}", inputDescriptor, outputDescriptor);
						logConstant("MethodType", inputDescriptor, outputDescriptor, inputName);
					} else {
						useLogger.trace("Skip method-type {} (unchanged)", inputDescriptor);
					}
					break;
				}

				case ConstantPool.CONSTANT_Utf8 :
					String inputUtf8 = constants.entry(constantNo);
					String outputUtf8 = transformString(inputName, "UTF8Constant", inputUtf8);
					if (outputUtf8 != null) {
						constants.entry(constantNo, outputUtf8);
						modifiedConstants++;
						useLogger.debug("UTF8: {} -> {}", inputUtf8, outputUtf8);
						logConstant("UTF8", inputUtf8, outputUtf8, inputName);
					} else {
						useLogger.trace("Skip UTF8 {} (unchanged)", inputUtf8);
					}

					break;

				case ConstantPool.CONSTANT_String : {
					StringInfo stringInfo = constants.entry(constantNo);
					String inputString = constants.utf8(stringInfo.string_index);
					String outputString = transformString(inputName, "StringConstant", inputString);

					if (outputString != null) {
						constants.entry(constantNo, new StringInfo(constants.utf8Info(outputString)));
						modifiedConstants++;
						useLogger.debug("String: {} -> {}", inputString, outputString);
						logConstant("String", inputString, outputString, inputName);
					} else {
						useLogger.trace("Skip string {} (unchanged)", inputString);
					}
					break;
				}

				case ConstantPool.CONSTANT_Fieldref :
				case ConstantPool.CONSTANT_Methodref :
				case ConstantPool.CONSTANT_InterfaceMethodref :
				case ConstantPool.CONSTANT_MethodHandle :
				case ConstantPool.CONSTANT_Dynamic :
				case ConstantPool.CONSTANT_InvokeDynamic :
				case ConstantPool.CONSTANT_Module :
				case ConstantPool.CONSTANT_Package :
				case ConstantPool.CONSTANT_Integer :
				case ConstantPool.CONSTANT_Float :
					useLogger.trace("Skip other (ignored)");
					break;

				case ConstantPool.CONSTANT_Long :
				case ConstantPool.CONSTANT_Double :
					useLogger.trace("Skip floating point value +1 (ignored)");
					// For some insane optimization reason, the Long(5) and
					// Double(6) entries take two slots in the constant pool.
					// See 4.4.5
					constantNo++;
					break;

				default :
					throw new TransformException("Unrecognized constant pool entry [ " + constantNo + " ]:" + " [ "
						+ constants.entry(constantNo) + " ]");
			}
		}

		return modifiedConstants;
	}
}
