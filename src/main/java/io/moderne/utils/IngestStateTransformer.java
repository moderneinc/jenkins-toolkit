package io.moderne.utils;

public interface IngestStateTransformer {
    IngestState transform(IngestState originalState);
}
