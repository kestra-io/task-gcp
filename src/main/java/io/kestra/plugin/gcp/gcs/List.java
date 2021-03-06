package io.kestra.plugin.gcp.gcs;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Storage;
import com.google.common.collect.Iterables;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.gcp.gcs.models.Blob;
import org.slf4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "List files in a bucket",
            code = {
                "from: \"gs://my_bucket/dir/\""
            }
        )
    }
)
@Schema(
    title = "List file on a GCS bucket."
)
public class List extends AbstractGcs implements RunnableTask<List.Output>, ListInterface {
    @NotNull
    private String from;

    @Schema(
        title = "If set to `true`, lists all versions of a blob. The default is `false`."
    )
    @PluginProperty(dynamic = true)
    private Boolean allVersions;

    @Schema(
        title = "The filter files or directory"
    )
    @Builder.Default
    private final Filter filter = Filter.BOTH;

    @Builder.Default
    private final ListingType listingType = ListingType.DIRECTORY;

    private String regExp;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Storage connection = this.connection(runContext);

        Logger logger = runContext.logger();
        URI from = encode(runContext, this.from);
        String regExp = runContext.render(this.regExp);

        Page<com.google.cloud.storage.Blob> list = connection.list(from.getAuthority(), options(from));

        java.util.List<Blob> blobs = StreamSupport
            .stream(list.iterateAll().spliterator(), false)
            .map(Blob::of)
            .filter(blob -> filter == Filter.DIRECTORY ? blob.isDirectory() :
                (filter != Filter.FILES || !blob.isDirectory())
            )
            .filter(blob -> regExp == null || blob.getUri().toString().matches(regExp))
            .collect(Collectors.toList());

        runContext.metric(Counter.of("size", blobs.size()));

        logger.debug("Found '{}' blobs from '{}'", blobs.size(), from);

        return Output
            .builder()
            .blobs(blobs)
            .build();
    }

    private Storage.BlobListOption[] options(URI from) {
        java.util.List<Storage.BlobListOption> options = new ArrayList<>();

        if (!from.getPath().equals("")) {
            options.add(Storage.BlobListOption.prefix(from.getPath().substring(1)));
        }

        if (this.allVersions != null) {
            options.add(Storage.BlobListOption.versions(this.allVersions));
        }

        if (this.listingType == ListingType.DIRECTORY) {
            options.add(Storage.BlobListOption.currentDirectory());
        }

        return Iterables.toArray(options, Storage.BlobListOption.class);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The list of blobs"
        )
        private final java.util.List<Blob> blobs;
    }
}
