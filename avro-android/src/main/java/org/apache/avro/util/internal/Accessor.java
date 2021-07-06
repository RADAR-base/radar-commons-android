/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro.util.internal;

import org.apache.avro.Schema;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.parsing.ResolvingGrammarGenerator;

import java.io.IOException;

public class Accessor {

  public abstract static class ResolvingGrammarGeneratorAccessor {
    protected abstract void encode(Encoder e, Schema s, Object n) throws IOException;
  }

  private static volatile ResolvingGrammarGeneratorAccessor resolvingGrammarGeneratorAccessor;

  public static void setAccessor(ResolvingGrammarGeneratorAccessor accessor) {
    if (resolvingGrammarGeneratorAccessor != null)
      throw new IllegalStateException("ResolvingGrammarGeneratorAccessor already initialized");
    resolvingGrammarGeneratorAccessor = accessor;
  }

  private static ResolvingGrammarGeneratorAccessor resolvingGrammarGeneratorAccessor() {
    if (resolvingGrammarGeneratorAccessor == null)
      ensureLoaded(ResolvingGrammarGenerator.class);
    return resolvingGrammarGeneratorAccessor;
  }

  private static void ensureLoaded(Class<?> c) {
    try {
      Class.forName(c.getName());
    } catch (ClassNotFoundException e) {
      // Shall never happen as the class is specified by its Class instance
    }
  }

  public static void encode(Encoder e, Schema s, Object n) throws IOException {
    resolvingGrammarGeneratorAccessor().encode(e, s, n);
  }
}
