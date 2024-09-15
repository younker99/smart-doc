/*
 * Copyright (C) 2018-2024 smart-doc
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.ly.doc.handler;

import com.ly.doc.model.DocJavaMethod;
import com.thoughtworks.qdox.model.JavaClass;

import java.util.List;

/**
 * handle customized operation for {@link DocJavaMethod}.
 *
 * @author yangyang
 */
@FunctionalInterface
public interface ICustomJavaMethodHandler {

	/**
	 * handle customized operation for {@link DocJavaMethod}.
	 * @param cls JavaClass
	 * @param methodList DocJavaMethod list
	 * @return {@code List<DocJavaMethod>}
	 */
	List<DocJavaMethod> apply(JavaClass cls, List<DocJavaMethod> methodList);

}
