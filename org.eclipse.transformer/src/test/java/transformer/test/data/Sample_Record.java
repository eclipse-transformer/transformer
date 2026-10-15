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

package transformer.test.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import javax.inject.Provider;

public record Sample_Record(Provider<String> provider, List<@Sample_Record.Type(Provider.class) Provider<String>> providers,
	Provider<?>[] array, @Sample_Record.Component(Provider.class) String unchanged, int count) {
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.RECORD_COMPONENT)
	public @interface Component {
		Class<?> value();
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE_USE)
	public @interface Type {
		Class<?> value();
	}
}
