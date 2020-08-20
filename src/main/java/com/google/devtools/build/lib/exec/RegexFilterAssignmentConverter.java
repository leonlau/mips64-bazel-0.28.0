// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.lib.util.RegexFilter.RegexFilterConverter;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.List;
import java.util.Map;

/** A converter for options of the form RegexFilter=String. */
public class RegexFilterAssignmentConverter
    implements Converter<Map.Entry<RegexFilter, List<String>>> {

  private final Splitter splitter = Splitter.on(',');

  @Override
  public Map.Entry<RegexFilter, List<String>> convert(String input) throws OptionsParsingException {
    int pos = input.indexOf('=');
    if (pos <= 0) {
      throw new OptionsParsingException(
          "Must be in the form of a 'regex=value[,value]' assignment");
    }
    List<String> value = splitter.splitToList(input.substring(pos + 1));
    if (value.contains("")) {
      // If the list contains exactly the empty string, it means an empty value was passed and we
      // should instead return an empty list.
      if (value.size() == 1) {
        value = ImmutableList.of();
      } else {
        throw new OptionsParsingException(
            "Values must not contain empty strings or leading / trailing commas");
      }
    }
    RegexFilter filter = new RegexFilterConverter().convert(input.substring(0, pos));
    return Maps.immutableEntry(filter, value);
  }

  @Override
  public String getTypeDescription() {
    return "a '<RegexFilter>=value[,value]' assignment";
  }
}
