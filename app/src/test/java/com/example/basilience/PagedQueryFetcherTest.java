package com.example.basilience;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.basilience.PagedQueryFetcher.ReadMode;

/**
 * The fake source below behaves like a Firestore query ordered by timestamp,
 * with the document ID as the implicit tie-break, and a startAfter(document)
 * cursor: a page is the next {limit} documents strictly after the cursor
 * document in that total order.
 */
public class PagedQueryFetcherTest {

    private static final int PAGE = PagedQueryFetcher.DEFAULT_PAGE_SIZE;

    static final class Doc {
        final String id;
        final long timestamp;
        final boolean malformed;

        Doc(String id, long timestamp, boolean malformed) {
            this.id = id;
            this.timestamp = timestamp;
            this.malformed = malformed;
        }
    }

    static final Comparator<Doc> ORDER = (a, b) -> {
        int byTime = Long.compare(a.timestamp, b.timestamp);
        return byTime != 0 ? byTime : a.id.compareTo(b.id);
    };

    /** The complete matching range, already in query order. */
    static List<Doc> dataset(int count, int distinctTimestamps, long seed, double malformedShare) {
        Random random = new Random(seed);
        List<Doc> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long ts = distinctTimestamps > 0 ? 1_000_000L + random.nextInt(distinctTimestamps) : 1_000_000L + i;
            docs.add(new Doc(String.format("doc%07d", random.nextInt(10_000_000)) + "_" + i, ts,
                    random.nextDouble() < malformedShare));
        }
        Collections.sort(docs, ORDER);
        return docs;
    }

    static class FakeSource implements PagedQueryFetcher.PageSource<Doc> {
        final List<Doc> all;
        final List<ReadMode> modes = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger requests = new AtomicInteger();
        boolean firstFromCache = false;
        int failOnRequest = -1;          // 1-based request number that fails
        int laterPagesFromOtherPlace = -1; // 1-based request number that reports the wrong origin
        Runnable afterResponse;          // runs just before the page is delivered
        ExecutorService async;           // null = deliver on the calling thread

        FakeSource(List<Doc> all) {
            this.all = all;
        }

        @Override
        public void fetchPage(Doc after, int limit, ReadMode mode, PagedQueryFetcher.PageCallback<Doc> callback) {
            int number = requests.incrementAndGet();
            modes.add(mode);
            Runnable work = () -> {
                if (number == failOnRequest) {
                    callback.onError(new RuntimeException("network lost on page " + number));
                    return;
                }
                int start = 0;
                if (after != null) {
                    // strictly after the cursor document in (timestamp, id) order
                    start = all.size();
                    for (int i = 0; i < all.size(); i++) {
                        if (ORDER.compare(all.get(i), after) > 0) { start = i; break; }
                    }
                }
                int end = Math.min(all.size(), start + limit);
                List<Doc> page = new ArrayList<>(all.subList(start, end));
                boolean fromCache = number == laterPagesFromOtherPlace ? !firstFromCache : firstFromCache;
                if (afterResponse != null) afterResponse.run();
                callback.onPage(new PagedQueryFetcher.Page<>(page, fromCache));
            };
            if (async != null) async.execute(work); else work.run();
        }
    }

    static final class Outcome {
        PagedQueryFetcher.Result<String> result;
        Exception error;
        final AtomicInteger deliveries = new AtomicInteger();
    }

    static List<String> parse(List<Doc> page) {
        List<String> out = new ArrayList<>();
        for (Doc d : page) if (!d.malformed) out.add(d.id);
        return out;
    }

    static Outcome run(FakeSource source, AtomicBoolean cancelled) {
        Outcome outcome = new Outcome();
        PagedQueryFetcher.fetch(source, PAGE, cancelled::get, PagedQueryFetcherTest::parse,
                new PagedQueryFetcher.Callback<String>() {
                    @Override public void onComplete(PagedQueryFetcher.Result<String> result) {
                        outcome.deliveries.incrementAndGet();
                        outcome.result = result;
                    }
                    @Override public void onError(Exception error) {
                        outcome.deliveries.incrementAndGet();
                        outcome.error = error;
                    }
                });
        return outcome;
    }

    static List<String> expected(List<Doc> all) {
        return parse(all); // what one un-paged query followed by the same parser would give
    }

    private void assertSameAsSingleQuery(List<Doc> all, int expectedRequests) {
        FakeSource source = new FakeSource(all);
        Outcome o = run(source, new AtomicBoolean(false));
        assertNull(o.error);
        assertNotNull(o.result);
        assertEquals("delivered exactly once", 1, o.deliveries.get());
        assertEquals(expected(all), o.result.items);
        assertEquals(expectedRequests, source.requests.get());
        assertEquals(expectedRequests, o.result.pageCount);
        assertFalse(o.result.fromCache);
    }

    @Test public void zeroDocuments() { assertSameAsSingleQuery(dataset(0, 0, 1, 0), 1); }
    @Test public void oneDocument() { assertSameAsSingleQuery(dataset(1, 0, 2, 0), 1); }
    @Test public void justUnderOnePage() { assertSameAsSingleQuery(dataset(999, 0, 3, 0), 1); }
    // A full page cannot prove the range ended, so one more (empty) page is requested.
    @Test public void exactlyOnePage() { assertSameAsSingleQuery(dataset(1000, 0, 4, 0), 2); }
    @Test public void justOverOnePage() { assertSameAsSingleQuery(dataset(1001, 0, 5, 0), 2); }
    @Test public void exactlyTwoPages() { assertSameAsSingleQuery(dataset(2000, 0, 6, 0), 3); }
    @Test public void exactlyTenPages() { assertSameAsSingleQuery(dataset(10_000, 0, 7, 0), 11); }
    @Test public void justOverTwoPages() { assertSameAsSingleQuery(dataset(2001, 0, 8, 0), 3); }
    @Test public void twentyFiveThousand() { assertSameAsSingleQuery(dataset(25_000, 0, 9, 0), 26); }
    @Test public void thirtyThousand() { assertSameAsSingleQuery(dataset(30_000, 0, 10, 0), 31); }

    @Test public void manyDuplicateTimestampsAcrossPageBoundaries() {
        // 5,000 documents over only 40 distinct timestamps: every page boundary falls inside a run of equal timestamps.
        List<Doc> all = dataset(5000, 40, 11, 0);
        assertSameAsSingleQuery(all, 6);
        // No document skipped or repeated at any boundary.
        FakeSource source = new FakeSource(all);
        Outcome o = run(source, new AtomicBoolean(false));
        assertEquals(all.size(), new java.util.HashSet<>(o.result.items).size());
    }

    @Test public void allDocumentsShareOneTimestamp() {
        assertSameAsSingleQuery(dataset(3500, 1, 12, 0), 4);
    }

    @Test public void boundaryRunOfEqualTimestampsStraddlesCursorExactly() {
        // Page 1 ends in the middle of a run of 300 documents with the same timestamp.
        List<Doc> docs = new ArrayList<>();
        for (int i = 0; i < 850; i++) docs.add(new Doc(String.format("a%04d", i), 100 + i, false));
        for (int i = 0; i < 300; i++) docs.add(new Doc(String.format("b%04d", i), 5000, false));
        for (int i = 0; i < 400; i++) docs.add(new Doc(String.format("c%04d", i), 6000 + i, false));
        Collections.sort(docs, ORDER);
        assertSameAsSingleQuery(docs, 2);
    }

    @Test public void malformedDocumentsAreSkippedByTheParserNotByPaging() {
        List<Doc> all = dataset(4321, 200, 13, 0.15);
        FakeSource source = new FakeSource(all);
        Outcome o = run(source, new AtomicBoolean(false));
        assertNull(o.error);
        assertEquals(expected(all), o.result.items);
        assertTrue(o.result.items.size() < all.size());
        // paging is driven by fetched documents, not by how many survived parsing
        assertEquals(5, source.requests.get());
    }

    @Test public void firstPageFromServerForcesServerForTheRest() {
        FakeSource source = new FakeSource(dataset(3500, 0, 14, 0));
        Outcome o = run(source, new AtomicBoolean(false));
        assertNull(o.error);
        assertEquals(java.util.Arrays.asList(ReadMode.DEFAULT, ReadMode.SERVER, ReadMode.SERVER, ReadMode.SERVER), source.modes);
        assertFalse(o.result.fromCache);
    }

    @Test public void firstPageFromCacheKeepsTheRestOnCacheAndSaysSo() {
        FakeSource source = new FakeSource(dataset(2500, 0, 15, 0));
        source.firstFromCache = true;
        Outcome o = run(source, new AtomicBoolean(false));
        assertNull(o.error);
        assertEquals(java.util.Arrays.asList(ReadMode.DEFAULT, ReadMode.CACHE, ReadMode.CACHE), source.modes);
        assertTrue(o.result.fromCache);
        assertEquals(expected(source.all), o.result.items);
    }

    @Test public void laterPageFromADifferentPlaceFailsInsteadOfMixingViews() {
        FakeSource source = new FakeSource(dataset(3500, 0, 16, 0));
        source.laterPagesFromOtherPlace = 3;
        Outcome o = run(source, new AtomicBoolean(false));
        assertNull(o.result);
        assertNotNull(o.error);
        assertEquals(1, o.deliveries.get());
        assertEquals(3, source.requests.get());
    }

    @Test public void failureOnAnIntermediatePageFailsTheWholeLoad() {
        FakeSource source = new FakeSource(dataset(5000, 0, 17, 0));
        source.failOnRequest = 3;
        Outcome o = run(source, new AtomicBoolean(false));
        assertNull("no partial result", o.result);
        assertNotNull(o.error);
        assertEquals(1, o.deliveries.get());
        assertEquals("nothing requested after the failure", 3, source.requests.get());
    }

    @Test public void failureOnTheFirstPage() {
        FakeSource source = new FakeSource(dataset(100, 0, 18, 0));
        source.failOnRequest = 1;
        Outcome o = run(source, new AtomicBoolean(false));
        assertNull(o.result);
        assertNotNull(o.error);
    }

    @Test public void parserFailureFailsTheLoadOnce() {
        FakeSource source = new FakeSource(dataset(2500, 0, 19, 0));
        Outcome outcome = new Outcome();
        PagedQueryFetcher.fetch(source, PAGE, () -> false, page -> { throw new IllegalStateException("bad page"); },
                new PagedQueryFetcher.Callback<String>() {
                    @Override public void onComplete(PagedQueryFetcher.Result<String> r) { outcome.deliveries.incrementAndGet(); }
                    @Override public void onError(Exception e) { outcome.deliveries.incrementAndGet(); outcome.error = e; }
                });
        assertEquals(1, outcome.deliveries.get());
        assertNotNull(outcome.error);
        assertEquals(1, source.requests.get());
    }

    @Test public void cancelledBeforeTheFirstRequestDoesNothing() {
        FakeSource source = new FakeSource(dataset(5000, 0, 20, 0));
        Outcome o = run(source, new AtomicBoolean(true));
        assertEquals(0, source.requests.get());
        assertEquals(0, o.deliveries.get());
    }

    @Test public void cancelledBetweenPagesStopsPagingAndNeverDelivers() {
        FakeSource source = new FakeSource(dataset(9000, 0, 21, 0));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // The user changes the period while page 2 is in flight.
        source.afterResponse = () -> { if (source.requests.get() == 2) cancelled.set(true); };
        Outcome o = run(source, cancelled);
        assertEquals("no request after cancellation", 2, source.requests.get());
        assertEquals("nothing delivered", 0, o.deliveries.get());
    }

    @Test public void cancelledAfterTheLastPageResponseNeverDelivers() {
        FakeSource source = new FakeSource(dataset(1500, 0, 22, 0));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        source.afterResponse = () -> { if (source.requests.get() == 2) cancelled.set(true); };
        Outcome o = run(source, cancelled);
        assertEquals(0, o.deliveries.get());
    }

    @Test public void cancelledWhileAFailureIsBeingReportedNeverDeliversTheError() {
        List<Doc> all = dataset(3000, 0, 23, 0);
        FakeSource inner = new FakeSource(all);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        PagedQueryFetcher.PageSource<Doc> source = (after, limit, mode, cb) -> {
            if (inner.requests.get() == 1) {          // page 2 fails, but the user already moved on
                cancelled.set(true);
                cb.onError(new RuntimeException("late failure"));
                return;
            }
            inner.fetchPage(after, limit, mode, cb);
        };
        Outcome outcome = new Outcome();
        PagedQueryFetcher.fetch(source, PAGE, cancelled::get, PagedQueryFetcherTest::parse,
                new PagedQueryFetcher.Callback<String>() {
                    @Override public void onComplete(PagedQueryFetcher.Result<String> r) { outcome.deliveries.incrementAndGet(); }
                    @Override public void onError(Exception e) { outcome.deliveries.incrementAndGet(); }
                });
        assertEquals(0, outcome.deliveries.get());
    }

    @Test public void worksWhenPagesArriveOnOtherThreads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Doc> all = dataset(12_345, 500, 25, 0.02);
            FakeSource source = new FakeSource(all);
            source.async = pool;
            CountDownLatch done = new CountDownLatch(1);
            Outcome outcome = new Outcome();
            PagedQueryFetcher.fetch(source, PAGE, () -> false, PagedQueryFetcherTest::parse,
                    new PagedQueryFetcher.Callback<String>() {
                        @Override public void onComplete(PagedQueryFetcher.Result<String> r) {
                            outcome.deliveries.incrementAndGet(); outcome.result = r; done.countDown();
                        }
                        @Override public void onError(Exception e) {
                            outcome.deliveries.incrementAndGet(); outcome.error = e; done.countDown();
                        }
                    });
            assertTrue(done.await(20, TimeUnit.SECONDS));
            assertNull(outcome.error);
            assertEquals(expected(all), outcome.result.items);
            assertEquals(13, outcome.result.pageCount);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void requestsAreSequentialEachCursorIsTheLastDocumentOfThePreviousPage() {
        List<Doc> all = dataset(3200, 60, 26, 0);
        List<Doc> cursors = new ArrayList<>();
        PagedQueryFetcher.PageSource<Doc> inner = new FakeSource(all);
        PagedQueryFetcher.PageSource<Doc> recording = (after, limit, mode, cb) -> {
            cursors.add(after);
            assertEquals(PAGE, limit);
            inner.fetchPage(after, limit, mode, cb);
        };
        PagedQueryFetcher.fetch(recording, PAGE, () -> false, PagedQueryFetcherTest::parse,
                new PagedQueryFetcher.Callback<String>() {
                    @Override public void onComplete(PagedQueryFetcher.Result<String> r) { }
                    @Override public void onError(Exception e) { throw new AssertionError(e); }
                });
        assertEquals(4, cursors.size());
        assertNull(cursors.get(0));
        assertEquals(all.get(999), cursors.get(1));
        assertEquals(all.get(1999), cursors.get(2));
        assertEquals(all.get(2999), cursors.get(3));
    }
}
