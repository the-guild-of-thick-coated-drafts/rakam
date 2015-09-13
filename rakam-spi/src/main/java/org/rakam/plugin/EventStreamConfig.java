/*
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
package org.rakam.plugin;

import io.airlift.configuration.Config;

/**
 * Created by buremba <Burak Emre Kabakcı> on 10/09/15 03:56.
 */
public class EventStreamConfig {
    private boolean enabled;

    @Config("event.stream.enabled")
    public void setEventStreamEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEventStreamEnabled() {
        return enabled;
    }
}