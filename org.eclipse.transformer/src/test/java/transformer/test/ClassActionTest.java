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

package transformer.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import aQute.bnd.classfile.Attribute;
import aQute.bnd.classfile.ClassFile;
import aQute.bnd.classfile.ElementInfo;
import aQute.bnd.classfile.EnclosingMethodAttribute;
import aQute.bnd.classfile.ModuleAttribute;
import aQute.bnd.classfile.ModuleMainClassAttribute;
import aQute.bnd.classfile.ModulePackagesAttribute;
import aQute.bnd.classfile.NestHostAttribute;
import aQute.bnd.classfile.NestMembersAttribute;
import aQute.bnd.classfile.builder.ClassFileBuilder;
import aQute.bnd.classfile.builder.ModuleInfoBuilder;
import aQute.lib.io.ByteBufferDataInput;
import aQute.lib.io.ByteBufferDataOutput;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.eclipse.transformer.action.ActionContext;
import org.eclipse.transformer.action.ByteData;
import org.eclipse.transformer.action.impl.ByteDataImpl;
import org.eclipse.transformer.action.impl.ClassActionImpl;
import org.eclipse.transformer.action.impl.SelectionRuleImpl;
import org.eclipse.transformer.action.impl.SignatureRuleImpl;
import org.eclipse.transformer.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import transformer.test.data.Sample_Record;

public class ClassActionTest {
	Logger	logger;
	String	testName;

	@BeforeEach
	public void setUp(TestInfo testInfo) {
		testName = testInfo.getTestClass()
			.map(Class::getName)
			.get() + "."
			+ testInfo.getTestMethod()
				.map(Method::getName)
				.get();
		logger = LoggerFactory.getLogger(testName);
	}

	<ATTRIBUTE extends Attribute> Stream<ATTRIBUTE> attributes(Class<ATTRIBUTE> attributeType, ElementInfo element) {
		@SuppressWarnings("unchecked")
		Stream<ATTRIBUTE> stream = (Stream<ATTRIBUTE>) Arrays.stream(element.attributes)
			.filter(attributeType::isInstance);
		return stream;
	}

	<ATTRIBUTE extends Attribute> Optional<ATTRIBUTE> attribute(Class<ATTRIBUTE> attributeType, ElementInfo element) {
		return attributes(attributeType, element).findFirst();
	}

	@Test
	public void module_transform() throws Exception {
		ClassFile original = new ModuleInfoBuilder().module_name("module.name")
			.module_version("1.0.0")
			.requires("requires.something", 0, "1.2.0")
			.requires("original.requires", ModuleAttribute.Require.ACC_TRANSITIVE, "1.1.0")
			.exports("pkg/something", 0, "another.module")
			.exports("original/exports", 0)
			.opens("pkg/opens", 0, "another.module")
			.opens("original/opens", 0, "another.module")
			.uses("pkg/uses/SomeInterface")
			.uses("original/uses/Interface")
			.provides("pkg/provides/SomeInterface", "pkg/provides/impl/SomeImpl")
			.provides("original/provides/Interface", "original/provides/impl/SomeImpl")
			.mainClass("original/main/Main")
			.build();
		assertThat(original.this_class).as("test class name is a module")
			.isEqualTo("module-info");
		assertThat(attribute(ModuleAttribute.class, original)).as("test class has a %s attribute", ModuleAttribute.NAME)
			.isNotEmpty();
		assertThat(attribute(ModulePackagesAttribute.class, original))
			.as("test class has a %s attribute", ModulePackagesAttribute.NAME)
			.isNotEmpty();
		assertThat(attribute(ModuleMainClassAttribute.class, original))
			.as("test class has a %s attribute", ModuleMainClassAttribute.NAME)
			.isNotEmpty();

		ModuleAttribute originalModule = attribute(ModuleAttribute.class, original)
			.orElseGet(() -> fail("missing attribute %s", ModuleAttribute.NAME));

		ByteBufferDataOutput dataOutput = new ByteBufferDataOutput();
		original.write(dataOutput);
		ByteData inputData = new ByteDataImpl(testName, dataOutput.toByteBuffer(), FileUtils.DEFAULT_CHARSET);

		Map<String, String> renames = new HashMap<>();
		renames.put("original.exports", "transformed.exports");
		renames.put("original.opens", "transformed.opens");
		renames.put("original.uses", "transformed.uses");
		renames.put("original.provides", "transformed.provides");
		renames.put("original.provides.impl", "transformed.provides.impl");
		renames.put("original.main", "transformed.main");

		ActionContext context = new ActionContext(logger,
			new SelectionRuleImpl(logger, Collections.emptyMap(), Collections.emptyMap()),
			new SignatureRuleImpl(logger, renames, null, null, null, null, null, Collections.emptyMap()));
		ClassActionImpl classAction = new ClassActionImpl(context);

		ByteData outputData = classAction.apply(inputData);

		ClassFile transformed = ClassFile.parseClassFile(ByteBufferDataInput.wrap(outputData.buffer()));

		assertThat(transformed.this_class).as("transformed class name is a module")
			.isEqualTo("module-info");

		Optional<ModuleAttribute> attribute = attribute(ModuleAttribute.class, transformed);

		assertThat(attribute).as("transformed class module name, flags, and version")
			.get()
			.usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
				.withIgnoredFields("requires", "exports", "opens", "uses", "provides")
				.build())
			.isEqualTo(originalModule);

		assertThat(attribute).map(m -> m.requires)
			.get(InstanceOfAssertFactories.array(ModuleAttribute.Require[].class))
			.as("transformed class module requires")
			.usingRecursiveFieldByFieldElementComparatorIgnoringFields()
			.isEqualTo(originalModule.requires);

		assertThat(attribute).map(m -> m.opens)
			.get(InstanceOfAssertFactories.array(ModuleAttribute.Open[].class))
			.as("transformed class module opens flags and to")
			.usingRecursiveFieldByFieldElementComparatorIgnoringFields("opens")
			.isEqualTo(originalModule.opens);
		assertThat(attribute).map(m -> m.opens)
			.get(InstanceOfAssertFactories.array(ModuleAttribute.Open[].class))
			.as("transformed class module opens packages")
			.extracting(o -> o.opens)
			.containsExactlyInAnyOrder("pkg/opens", "transformed/opens");

		assertThat(attribute).map(m -> m.exports)
			.get(InstanceOfAssertFactories.array(ModuleAttribute.Export[].class))
			.as("transformed class module exports flags and to")
			.usingRecursiveFieldByFieldElementComparatorIgnoringFields("exports")
			.isEqualTo(originalModule.exports);
		assertThat(attribute).map(m -> m.exports)
			.get(InstanceOfAssertFactories.array(ModuleAttribute.Export[].class))
			.as("transformed class module exports packages")
			.extracting(o -> o.exports)
			.containsExactlyInAnyOrder("pkg/something", "transformed/exports");

		assertThat(attribute).map(m -> m.provides)
			.get(InstanceOfAssertFactories.array(ModuleAttribute.Provide[].class))
			.as("transformed class module provides packages")
			.extracting(p -> p.provides)
			.containsExactlyInAnyOrder("pkg/provides/SomeInterface", "transformed/provides/Interface");
		assertThat(attribute).map(m -> m.provides)
			.get(InstanceOfAssertFactories.array(ModuleAttribute.Provide[].class))
			.as("transformed class module provides packages")
			.flatExtracting(p -> Arrays.asList(p.provides_with))
			.containsExactlyInAnyOrder("pkg/provides/impl/SomeImpl", "transformed/provides/impl/SomeImpl");

		assertThat(attribute(ModulePackagesAttribute.class, transformed)).as("transformed class module packages")
			.map(m -> m.packages)
			.get(InstanceOfAssertFactories.array(String[].class))
			.noneMatch(p -> p.contains("original"));

		assertThat(attribute(ModuleMainClassAttribute.class, transformed)).as("transformed class module main class")
			.map(m -> m.main_class)
			.get(InstanceOfAssertFactories.STRING)
			.isEqualTo("transformed/main/Main");
	}

	@Test
	public void nest_transform() throws Exception {
		ClassFileBuilder builder = new ClassFileBuilder(Modifier.PUBLIC, ClassFile.MAJOR_VERSION, 0, "nest/Test",
			"java/lang/Object");
		builder.attributes(new NestHostAttribute("original/host/NestHost"));
		builder.attributes(new NestMembersAttribute(new String[] {
			"original/member/NestMember1", "pkg/member/NestMember2"
		}));
		ClassFile original = builder.build();
		assertThat(original.this_class).as("test class name")
			.isEqualTo("nest/Test");
		assertThat(attribute(NestHostAttribute.class, original))
			.as("test class has a %s attribute", NestHostAttribute.NAME)
			.isNotEmpty();
		assertThat(attribute(NestMembersAttribute.class, original))
			.as("test class has a %s attribute", NestMembersAttribute.NAME)
			.isNotEmpty();

		ByteBufferDataOutput dataOutput = new ByteBufferDataOutput();
		original.write(dataOutput);
		ByteData inputData = new ByteDataImpl(testName, dataOutput.toByteBuffer(), FileUtils.DEFAULT_CHARSET);

		Map<String, String> renames = new HashMap<>();
		renames.put("original.host", "transformed.host");
		renames.put("original.member", "transformed.member");
		ActionContext context = new ActionContext(logger,
			new SelectionRuleImpl(logger, Collections.emptyMap(), Collections.emptyMap()),
			new SignatureRuleImpl(logger, renames, null, null, null, null, null, Collections.emptyMap()));

		ClassActionImpl classAction = new ClassActionImpl(context);
		ByteData outputData = classAction.apply(inputData);

		ClassFile transformed = ClassFile.parseClassFile(ByteBufferDataInput.wrap(outputData.buffer()));

		assertThat(transformed.this_class).as("transformed class name")
			.isEqualTo("nest/Test");

		assertThat(attribute(NestHostAttribute.class, transformed)).as("transformed class nest host")
			.map(h -> h.host_class)
			.get(InstanceOfAssertFactories.STRING)
			.isEqualTo("transformed/host/NestHost");

		assertThat(attribute(NestMembersAttribute.class, transformed)).as("transformed class nest members")
			.map(m -> m.classes)
			.get(InstanceOfAssertFactories.array(String[].class))
			.containsExactlyInAnyOrder("transformed/member/NestMember1", "pkg/member/NestMember2");
	}

	@Test
	public void record_transform() throws Exception {
		String resourceName = Sample_Record.class.getName().replace('.', '/') + ".class";
		ByteData inputData;
		try (var input = Sample_Record.class.getClassLoader().getResourceAsStream(resourceName)) {
			assertThat(input).isNotNull();
			inputData = new ByteDataImpl(resourceName, ByteBuffer.wrap(input.readAllBytes()), FileUtils.DEFAULT_CHARSET);
		}
		ActionContext context = new ActionContext(logger,
			new SelectionRuleImpl(logger, Collections.emptyMap(), Collections.emptyMap()),
			new SignatureRuleImpl(logger, Map.of("javax.inject", "jakarta.inject"), null, null, null, null, null,
				Collections.emptyMap()));
		ByteData outputData = new ClassActionImpl(context).apply(inputData);
		Class<?> transformed = new ClassLoader(Sample_Record.class.getClassLoader()) {
			Class<?> defineRecord() {
				return defineClass(null, outputData.buffer(), null);
			}
		}.defineRecord();

		assertThat(transformed.isRecord()).isTrue();
		RecordComponent[] components = transformed.getRecordComponents();
		assertThat(components).extracting(RecordComponent::getName)
			.containsExactly("provider", "providers", "array", "unchanged", "count");
		assertThat(components).extracting(RecordComponent::getType)
			.containsExactly(jakarta.inject.Provider.class, List.class, jakarta.inject.Provider[].class, String.class,
				int.class);
		for (RecordComponent component : components) {
			assertThat(component.getAccessor().getReturnType()).isEqualTo(component.getType());
			assertThat(component.getAccessor().getGenericReturnType()).isEqualTo(component.getGenericType());
		}

		ParameterizedType providerType = (ParameterizedType) components[0].getGenericType();
		assertThat(providerType.getRawType()).isEqualTo(jakarta.inject.Provider.class);
		assertThat(providerType.getActualTypeArguments()).containsExactly(String.class);
		ParameterizedType providersType = (ParameterizedType) components[1].getGenericType();
		assertThat(providersType.getActualTypeArguments()).containsExactly(providerType);
		AnnotatedParameterizedType annotatedProviders = (AnnotatedParameterizedType) components[1].getAnnotatedType();
		assertThat(annotatedProviders.getAnnotatedActualTypeArguments()[0].getAnnotation(Sample_Record.Type.class).value())
			.isEqualTo(jakarta.inject.Provider.class);
		assertThat(components[3].getAnnotation(Sample_Record.Component.class).value())
			.isEqualTo(jakarta.inject.Provider.class);

		jakarta.inject.Provider<String> provider = () -> "value";
		Object record = transformed.getConstructor(jakarta.inject.Provider.class, List.class,
			jakarta.inject.Provider[].class, String.class, int.class)
			.newInstance(provider, List.of(provider), new jakarta.inject.Provider<?>[] {provider}, "unchanged", 42);
		assertThat(components[0].getAccessor().invoke(record)).isSameAs(provider);
		assertThat(components[3].getAccessor().invoke(record)).isEqualTo("unchanged");
		assertThat(components[4].getAccessor().invoke(record)).isEqualTo(42);
	}

	@Test
	public void record_unchanged() throws Exception {
		String resourceName = Sample_Record.class.getName().replace('.', '/') + ".class";
		ByteData inputData;
		try (var input = Sample_Record.class.getClassLoader().getResourceAsStream(resourceName)) {
			assertThat(input).isNotNull();
			inputData = new ByteDataImpl(resourceName, ByteBuffer.wrap(input.readAllBytes()), FileUtils.DEFAULT_CHARSET);
		}
		ActionContext context = new ActionContext(logger,
			new SelectionRuleImpl(logger, Collections.emptyMap(), Collections.emptyMap()),
			new SignatureRuleImpl(logger, Collections.emptyMap(), null, null, null, null, null, Collections.emptyMap()));
		ByteData outputData = new ClassActionImpl(context).apply(inputData);
		assertThat(outputData.name()).isEqualTo(inputData.name());
		assertThat(outputData.buffer()).isEqualTo(inputData.buffer());
	}

	@Test
	public void enclosing_method_transform() throws Exception {
		ClassFileBuilder builder = new ClassFileBuilder(Modifier.PUBLIC, ClassFile.MAJOR_VERSION, 0, "enclosing/Test",
			"java/lang/Object");
		builder.attributes(new EnclosingMethodAttribute("original/enclosing/Enclosing", "method",
			"(Loriginal/param/Param1;Lpkg/other/Param2;)Loriginal/result/Result;"));
		ClassFile original = builder.build();
		assertThat(original.this_class).as("test class name")
			.isEqualTo("enclosing/Test");
		assertThat(attribute(EnclosingMethodAttribute.class, original))
			.as("test class has a %s attribute", EnclosingMethodAttribute.NAME)
			.isNotEmpty();

		ByteBufferDataOutput dataOutput = new ByteBufferDataOutput();
		original.write(dataOutput);
		ByteData inputData = new ByteDataImpl(testName, dataOutput.toByteBuffer(), FileUtils.DEFAULT_CHARSET);

		Map<String, String> renames = new HashMap<>();
		renames.put("original.enclosing", "transformed.enclosing");
		renames.put("original.param", "transformed.param");
		renames.put("original.result", "transformed.result");
		ActionContext context = new ActionContext(logger,
			new SelectionRuleImpl(logger, Collections.emptyMap(), Collections.emptyMap()),
			new SignatureRuleImpl(logger, renames, null, null, null, null, null, Collections.emptyMap()));
		ClassActionImpl classAction = new ClassActionImpl(context);
		ByteData outputData = classAction.apply(inputData);

		ClassFile transformed = ClassFile.parseClassFile(ByteBufferDataInput.wrap(outputData.buffer()));

		assertThat(transformed.this_class).as("transformed class name")
			.isEqualTo("enclosing/Test");

		assertThat(attribute(EnclosingMethodAttribute.class, transformed)).as("transformed class enclosing method")
			.get()
			.usingRecursiveComparison()
			.isEqualTo(new EnclosingMethodAttribute("transformed/enclosing/Enclosing", "method",
				"(Ltransformed/param/Param1;Lpkg/other/Param2;)Ltransformed/result/Result;"));
	}
}
