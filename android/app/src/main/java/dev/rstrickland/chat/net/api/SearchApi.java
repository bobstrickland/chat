package dev.rstrickland.chat.net.api;

import dev.rstrickland.chat.model.SearchModels;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * Search service (port 3007). Message hits are membership-scoped server-side to
 * the caller's own conversations; people search is the public directory.
 */
public interface SearchApi {

    @GET("search")
    Call<SearchModels.SearchResults> search(@Query("q") String q, @Query("type") String type);
}
