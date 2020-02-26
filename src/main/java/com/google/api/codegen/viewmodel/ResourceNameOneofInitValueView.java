/* Copyright 2016 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.viewmodel;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

@AutoValue
public abstract class ResourceNameOneofInitValueView implements InitValueView {

  public abstract String resourceOneofTypeName();

  @Nullable
  public abstract ResourceNameInitValueView specificResourceNameView();

  @Nullable
  public abstract String createMethodName();

  @Nullable
  public abstract ImmutableList<String> formatArgs();

  public boolean useStaticCreateMethod() {
    return createMethodName() != null;
  }

  public String type() {
    return ResourceNameOneofInitValueView.class.getSimpleName();
  }

  public static Builder newBuilder() {
    return new AutoValue_ResourceNameOneofInitValueView.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder resourceOneofTypeName(String val);

    public abstract Builder specificResourceNameView(ResourceNameInitValueView val);

    public abstract Builder createMethodName(String val);

    public abstract Builder formatArgs(ImmutableList<String> val);

    public abstract ResourceNameOneofInitValueView build();
  }
}
