package com.example.basilience;

import androidx.annotation.NonNull;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

/**
 * Reads pages of one ordered Firestore query for {@link PagedQueryFetcher}.
 *
 * The base query keeps its filters and order for every page; only limit and
 * the startAfter cursor change. Responses are delivered on the report worker
 * threads, so a page is parsed off the main thread and its QuerySnapshot is
 * released as soon as the caller has copied out what it needs.
 */
final class FirestoreQueryPageSource implements PagedQueryFetcher.PageSource<DocumentSnapshot> {

    private final Query base;

    FirestoreQueryPageSource(@NonNull Query base) {
        this.base = base;
    }

    @Override
    public void fetchPage(DocumentSnapshot after, int limit, PagedQueryFetcher.ReadMode mode,
                          PagedQueryFetcher.PageCallback<DocumentSnapshot> callback) {
        Query page = base.limit(limit);
        if (after != null) page = page.startAfter(after);

        Source source;
        switch (mode) {
            case SERVER: source = Source.SERVER; break;
            case CACHE: source = Source.CACHE; break;
            default: source = Source.DEFAULT; break;
        }

        page.get(source).addOnCompleteListener(ReportWorker::run, task -> {
            if (!task.isSuccessful()) {
                Exception error = task.getException();
                callback.onError(error != null ? error : new IllegalStateException("Page request failed"));
                return;
            }
            QuerySnapshot snapshot = task.getResult();
            callback.onPage(new PagedQueryFetcher.Page<>(
                    snapshot.getDocuments(), snapshot.getMetadata().isFromCache()));
        });
    }
}
