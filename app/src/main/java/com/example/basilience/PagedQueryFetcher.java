package com.example.basilience;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Loads the COMPLETE result of an ordered, date-bounded query in pages.
 *
 * A page is a stand-in for the whole range, never a cap: pages are requested
 * one after another, each starting right after the last document of the
 * previous one, until a page comes back shorter than the page size. The
 * caller gets one result, and only after every page succeeded. Any failed
 * page fails the whole load; a report is never built from part of its range.
 *
 * Network/cache consistency: the first page is read normally. If it came from
 * the server, every later page must too (a dropped connection then fails the
 * load instead of a cache read quietly returning a short page that would look
 * like the end of the range). If the first page came from the local cache
 * (offline from the start), every later page is read from the cache as well,
 * and the result says so through {@link Result#fromCache}.
 *
 * Cancellation is checked before each page request, after each response, and
 * before delivery. Once cancelled nothing more is requested and the callback
 * is never invoked.
 *
 * Plain Java (no Android or Firestore types) so it can be tested with fake pages.
 */
final class PagedQueryFetcher {

    /** Initial page size; a page is about 300 KB on the wire and a couple of MB of heap. */
    static final int DEFAULT_PAGE_SIZE = 1000;

    enum ReadMode { DEFAULT, SERVER, CACHE }

    /** One page as the source reports it. */
    static final class Page<D> {
        final List<D> documents;
        final boolean fromCache;

        Page(List<D> documents, boolean fromCache) {
            this.documents = documents;
            this.fromCache = fromCache;
        }
    }

    interface PageCallback<D> {
        void onPage(Page<D> page);
        void onError(Exception error);
    }

    /**
     * Requests one page: the next {@code limit} documents after {@code after}
     * (or from the start when null), in the query's fixed order. Must call the
     * callback exactly once, on any thread.
     */
    interface PageSource<D> {
        void fetchPage(D after, int limit, ReadMode mode, PageCallback<D> callback);
    }

    interface Callback<T> {
        void onComplete(Result<T> result);
        void onError(Exception error);
    }

    static final class Result<T> {
        final List<T> items;
        /** True when the range was served from the local cache, so it may not be the complete history. */
        final boolean fromCache;
        final int pageCount;

        Result(List<T> items, boolean fromCache, int pageCount) {
            this.items = items;
            this.fromCache = fromCache;
            this.pageCount = pageCount;
        }
    }

    private PagedQueryFetcher() {}

    static <D, T> void fetch(PageSource<D> source, int pageSize, BooleanSupplier cancelled,
                             Function<List<D>, List<T>> parser, Callback<T> callback) {
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be at least 1");
        new Run<>(source, pageSize, cancelled, parser, callback).requestNext(null, ReadMode.DEFAULT);
    }

    private static final class Run<D, T> {
        private final PageSource<D> source;
        private final int pageSize;
        private final BooleanSupplier cancelled;
        private final Function<List<D>, List<T>> parser;
        private final Callback<T> callback;
        private final List<T> items = new ArrayList<>();
        private int pages = 0;
        private boolean firstPageFromCache = false;
        private boolean done = false;

        Run(PageSource<D> source, int pageSize, BooleanSupplier cancelled,
            Function<List<D>, List<T>> parser, Callback<T> callback) {
            this.source = source;
            this.pageSize = pageSize;
            this.cancelled = cancelled;
            this.parser = parser;
            this.callback = callback;
        }

        void requestNext(D after, ReadMode mode) {
            if (done || cancelled.getAsBoolean()) return;
            try {
                source.fetchPage(after, pageSize, mode, new PageCallback<D>() {
                    @Override
                    public void onPage(Page<D> page) {
                        handlePage(page);
                    }

                    @Override
                    public void onError(Exception error) {
                        fail(error);
                    }
                });
            } catch (RuntimeException e) {
                // After delivery, an exception is the caller's own and must not be swallowed here.
                if (done) throw e;
                fail(e);
            }
        }

        void handlePage(Page<D> page) {
            if (done || cancelled.getAsBoolean()) return;
            final List<D> documents = page.documents;
            try {
                pages++;
                if (pages == 1) {
                    firstPageFromCache = page.fromCache;
                } else if (page.fromCache != firstPageFromCache) {
                    // A later page must come from the same place as the first.
                    // Anything else means the range was assembled from two
                    // different views of the data.
                    throw new IllegalStateException("Page " + pages + " came from the "
                            + (page.fromCache ? "cache" : "server") + " but the first page came from the "
                            + (firstPageFromCache ? "cache" : "server"));
                }
                items.addAll(parser.apply(documents));
            } catch (RuntimeException e) {
                fail(e);
                return;
            }

            if (documents.size() < pageSize) {
                if (cancelled.getAsBoolean()) return;
                done = true;
                callback.onComplete(new Result<>(items, firstPageFromCache, pages));
                return;
            }
            D last = documents.get(documents.size() - 1);
            requestNext(last, firstPageFromCache ? ReadMode.CACHE : ReadMode.SERVER);
        }

        private void fail(Exception error) {
            if (done || cancelled.getAsBoolean()) return;
            done = true;
            callback.onError(error);
        }
    }
}
