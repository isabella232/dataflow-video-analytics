/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.solutions.df.video.analytics.common;

import com.google.auto.value.AutoValue;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.schemas.transforms.Filter;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.ToJson;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.apache.beam.sdk.values.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filters through all the annotated results and outputs to PubSub only the ones that match the
 * specified entities and confidence level.
 */
@AutoValue
public abstract class WriteRelevantAnnotationsToPubSubTransform
    extends PTransform<PCollection<Row>, PDone> {

  private static final Logger LOG =
      LoggerFactory.getLogger(WriteRelevantAnnotationsToPubSubTransform.class);

  public abstract String topicId();

  @Nullable
  public abstract List<String> entityList();

  @Nullable
  public abstract Double confidence();

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setTopicId(String topic);

    public abstract Builder setEntityList(List<String> entityLst);

    public abstract Builder setConfidence(Double confidence);

    public abstract WriteRelevantAnnotationsToPubSubTransform build();
  }

  public static Builder newBuilder() {
    return new AutoValue_WriteRelevantAnnotationsToPubSubTransform.Builder();
  }

  // [START loadSnippet_4]
  @Override
  public PDone expand(PCollection<Row> input) {

    return input
        .apply(
            "FilterByEntityAndConfidence",
            Filter.<Row>create()
                .whereFieldName(
                    "file_data.entity",
                    entity -> entityList().stream().anyMatch(obj -> obj.equals(entity)))
                .whereFieldName("file_data.confidence", (Double con) -> con > confidence()))
        .apply("ConvertToJson", ToJson.of())
        // [END loadSnippet_4]

        .apply(
            "PrettyPrint",
            ParDo.of(
                new DoFn<String, String>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    LOG.info("Json {}", c.element());
                    c.output(c.element());
                  }
                }))
        .apply("PublishToPubSub", PubsubIO.writeStrings().to(topicId()));
  }
}