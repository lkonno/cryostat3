/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat;

public class ConfigProperties {
    public static final String AWS_BUCKET_NAME_ARCHIVES = "storage.buckets.archives.name";
    public static final String AWS_OBJECT_EXPIRATION_LABELS =
            "storage.buckets.archives.expiration-label";

    public static final String GRAFANA_DASHBOARD_URL = "grafana-dashboard.url";
    public static final String GRAFANA_DASHBOARD_EXT_URL = "grafana-dashboard-ext.url";
    public static final String GRAFANA_DATASOURCE_URL = "grafana-datasource.url";
}
