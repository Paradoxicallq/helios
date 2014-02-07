package com.spotify.helios.agent;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Lists;

import com.kpelykh.docker.client.model.ContainerConfig;
import com.kpelykh.docker.client.model.HostConfig;
import com.kpelykh.docker.client.model.ImageInspectResponse;
import com.spotify.helios.common.descriptors.Job;

import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

/**
 * Bind mounts /usr/lib/helios inside the container as /helios, and uses the syslog-redirector
 * executable there to redirect container stdout/err to syslog.
 */
public class SyslogRedirectingCommandWrapper implements CommandWrapper {

  private final String syslogHostPort;

  public SyslogRedirectingCommandWrapper(String syslogHostPort) {
    this.syslogHostPort = syslogHostPort;
  }

  @Override
  public void modifyStartConfig(HostConfig hostConfig) {
    hostConfig.binds = new String[]{"/usr/lib/helios:/helios:ro"};
  }

  @Override
  public void modifyCreateConfig(String image, Job job, ImageInspectResponse imageInfo,
      ContainerConfig containerConfig) {
    ContainerConfig imageConfig = imageInfo.containerConfig;

    final List<String> entrypoint = Lists.newArrayList("/helios/syslog-redirector",
                                                       "-h", syslogHostPort,
                                                       "-n", job.getId().toString(),
                                                       "--");

    if (imageConfig.getEntrypoint() != null) {
      entrypoint.addAll(asList(imageConfig.getEntrypoint()));
    }

    containerConfig.setEntrypoint(entrypoint.toArray(new String[entrypoint.size()]));

    @SuppressWarnings("unchecked")
    Map<String, Object> volumes = (Map<String, Object>) containerConfig.getVolumes();
    final Builder<String, Object> builder = ImmutableMap.builder();
    if (volumes != null) {
      builder.putAll(volumes);
    }

    builder.put("/helios", ImmutableMap.<String, String>of());
    containerConfig.setVolumes(builder.build());
  }
}