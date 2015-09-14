package org.rakam.realtime;

import com.facebook.presto.sql.ExpressionFormatter;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.inject.Singleton;
import org.rakam.plugin.ContinuousQuery;
import org.rakam.plugin.ContinuousQueryService;
import org.rakam.report.QueryExecutor;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.Api;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.ApiResponse;
import org.rakam.server.http.annotations.ApiResponses;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.server.http.annotations.ParamBody;
import org.rakam.util.JsonHelper;
import org.rakam.util.JsonResponse;
import org.rakam.util.NotImplementedException;
import org.rakam.util.RakamException;

import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.rakam.util.JsonHelper.convert;

/**
 * Created by buremba <Burak Emre Kabakcı> on 02/02/15 14:30.
 */
@Singleton
@Api(value = "/realtime", description = "Realtime module", tags = "realtime",
        authorizations = @Authorization(value = "api_key", type = "api_key"))
@Path("/realtime")
public class RealTimeHttpService extends HttpService {
    private final ContinuousQueryService service;
    private final QueryExecutor executor;
    SqlParser sqlParser = new SqlParser();
    private final Duration slideInterval = Duration.ofSeconds(5);
    private final Duration window = Duration.ofSeconds(45);

    private static final Pattern NONLATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");

    @Inject
    public RealTimeHttpService(ContinuousQueryService service, QueryExecutor executor) {
        this.service = checkNotNull(service, "service is null");
        this.executor = checkNotNull(executor, "executor is null");
    }

    /**
     * Creates real-time report using continuous queries.
     * This module adds a new attribute called 'time' to events, it's simply a unix epoch that represents the seconds the event is occurred.
     * Continuous query continuously aggregates 'time' column and
     * real-time module executes queries on continuous query table similar to 'select count from stream_count where time > now() - interval 5 second'
     *
     * curl 'http://localhost:9999/realtime/create' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Events by collection", "aggregation": "COUNT"}'
     */
    @JsonRequest
    @ApiOperation(value = "Create realtime report")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.")})
    @Path("/create")
    public CompletableFuture<JsonResponse> create(@ParamBody RealTimeReport query) {
        String tableName = toSlug(query.name);

        String sqlQuery = new StringBuilder().append("select ")
                .append(format("(time / %d) as time, ", slideInterval.getSeconds()))
                .append(createSelect(query.aggregation, query.measure, query.dimension))
                .append(" from stream")
                .append(query.filter == null ? "" : "where " + query.filter)
                .append(query.dimension != null ? " group by 1, 2" : " group by 1").toString();

        ContinuousQuery report = new ContinuousQuery(query.project,
                query.name,
                tableName,
                sqlQuery,
                query.collections,
                ImmutableMap.of("type", "realtime", "report", query));
        return service.create(report).thenApply(JsonResponse::map);
    }

    /**
     * curl 'http://localhost:9999/realtime/get' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Events by collection", "aggregation": "COUNT"}'
     */
    @JsonRequest
    @POST
    @ApiOperation(value = "Get realtime report")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist."),
            @ApiResponse(code = 400, message = "Report does not exist.")})
    @Path("/get")
    public CompletableFuture<Object> get(@ApiParam(name = "project", required = true) String project,
                                         @ApiParam(name = "name", required = true) String name,
                                         @ApiParam(name = "filter", required = false) String filter,
                                         @ApiParam(name = "aggregate", required = false) boolean aggregate,
                                         @ApiParam(name = "date_start", required = false) Instant dateStart,
                                         @ApiParam(name = "date_end", required = false) Instant dateEnd) {
        Expression expression;
        if (filter != null) {
            expression = sqlParser.createExpression(filter);
        } else {
            expression = null;
        }

        ContinuousQuery continuousQuery = service.get(project, name);
        if (continuousQuery == null) {
            CompletableFuture<Object> f = new CompletableFuture<>();
            f.completeExceptionally(new RakamException("Couldn't found rule", 400));
            return f;
        }

        long last_update = Instant.now().getEpochSecond();
        long previousWindow = (dateStart == null ? (last_update - window.getSeconds()) : dateStart.getEpochSecond()) / 5;
        long currentWindow = (dateEnd == null ? last_update : dateEnd.getEpochSecond()) / 5;

        RealTimeReport report = JsonHelper.convert(continuousQuery.options.get("report"), RealTimeReport.class);

        Object timeCol = aggregate ? currentWindow : "time";
        String sqlQuery = format("select %s, %s %s(value) from %s where %s %s %s ORDER BY 1 ASC LIMIT 5000",
                timeCol,
                report.dimension != null ? report.dimension + "," : "",
                aggregate ? getAggregationMethod(report.aggregation) : "",
                "continuous." + continuousQuery.tableName,
                format("time >= %d", previousWindow) + (dateEnd == null ? "" : format("AND time <", format("time >= %d AND time <= %d", previousWindow, currentWindow))),
                report.dimension != null && aggregate ? "GROUP BY " + report.dimension : "",
                expression == null ? "" : ExpressionFormatter.formatExpression(expression));

        return executor.executeQuery(continuousQuery.project, sqlQuery).getResult().thenApply(result -> {
            if (!result.isFailed()) {

                long previousTimestamp = previousWindow * 5;
                long currentTimestamp = currentWindow * 5;

                List<List<Object>> data = result.getResult();

                if (!aggregate) {
                    if (report.dimension == null) {
                        List<List<Object>> newData = Lists.newLinkedList();
                        int currentDataIdx = 0;
                        for (long current = previousWindow; current < currentWindow; current++) {
                            if (data.size() > currentDataIdx) {
                                List<Object> objects = data.get(currentDataIdx++);
                                Long time = ((Number) objects.get(0)).longValue();
                                if (time == current) {
                                    newData.add(ImmutableList.of(current * 5, objects.get(1)));
                                    continue;
                                }
                            }
                            newData.add(ImmutableList.of(current * 5, 0));
                        }
                        return new RealTimeQueryResult(previousTimestamp, currentTimestamp, newData);
                    } else {
                        Map<Object, List<Object>> newData = data.stream()
                                .collect(Collectors.groupingBy(o -> new Function<List<Object>, Object>() {
                                            @Override
                                            public Object apply(List<Object> o) {
                                                return o.get(0);
                                            }
                                        },
                                        Collectors.mapping(l -> ImmutableList.of(l.get(1), l.get(2)), Collectors.toList())));
                        return new RealTimeQueryResult(previousTimestamp, currentTimestamp, newData);
                    }
                } else {
                    if (report.dimension == null) {
                        return new RealTimeQueryResult(previousTimestamp, currentTimestamp, data.size() > 0 ? data.get(0).get(1) : 0);
                    } else {
                        List<ImmutableList<Object>> newData = data.stream()
                                .map(m -> ImmutableList.of(m.get(1), m.get(2)))
                                .collect(Collectors.toList());
                        return new RealTimeQueryResult(previousTimestamp, currentTimestamp, newData);
                    }
                }
            }
            return result;
        });
    }

    private String getAggregationMethod(AggregationType aggregation) {
        switch (aggregation) {
            case COUNT:
            case SUM:
                return "sum";
            case MINIMUM:
                return "min";
            case MAXIMUM:
                return "max";
            case AVERAGE:
                throw new UnsupportedOperationException();
            default:
                throw new NotImplementedException();
        }
    }

    public static class RealTimeQueryResult {
        public final long start;
        public final long end;
        public final Object result;

        public RealTimeQueryResult(long start, long end, Object result) {
            this.start = start;
            this.end = end;
            this.result = result;
        }
    }

    /**
     * curl 'http://localhost:9999/realtime/list' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId"}'
     */
    @JsonRequest
    @ApiOperation(value = "List real-time reports")
    @Path("/list")
    public List<RealTimeReport> list(@ApiParam(name = "project", required = true) String project) {
        if (project == null) {
            throw new RakamException("project parameter is required", 400);
        }
        return service.list(project).stream()
                .filter(report -> report.options != null && Objects.equals(report.options.get("type"), "realtime"))
                .map(report -> convert(report.options.get("report"), RealTimeReport.class))
                .collect(Collectors.toList());
    }


    /**
     * curl 'http://localhost:9999/realtime/delete' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Events by collection"}'
     */
    @JsonRequest
    @ApiOperation(value = "Delete realtime report")
    @Path("/delete")
    public Object delete(@ApiParam(name = "project", required = true) String project,
                         @ApiParam(name = "name", required = true) String name) {

        // TODO: Check if it's a real-time report.
        service.delete(project, name);
        return JsonHelper.jsonObject().put("message", "successfully deleted");
    }

    public String createSelect(AggregationType aggType, String measure, String dimension) {

        if (measure == null) {
            if (aggType != AggregationType.COUNT)
                throw new IllegalArgumentException("either measure.expression or measure.field must be specified.");
        }

        StringBuilder builder = new StringBuilder();
        if (dimension != null)
            builder.append(" " + dimension + ", ");

        switch (aggType) {
            case AVERAGE:
                return builder.append("avg(1) as value").toString();
            case MAXIMUM:
                return builder.append("max(1) as value").toString();
            case MINIMUM:
                return builder.append("min(1) as value").toString();
            case COUNT:
                return builder.append("count(1) as value").toString();
            case SUM:
                return builder.append("sum(1) as value").toString();
            case APPROXIMATE_UNIQUE:
                return builder.append("approx_distinct(1) as value").toString();
            case VARIANCE:
                return builder.append("variance(1) as value").toString();
            case POPULATION_VARIANCE:
                return builder.append("variance(1) as value").toString();
            case STANDARD_DEVIATION:
                return builder.append("stddev(1) as value").toString();
            default:
                throw new IllegalArgumentException("aggregation type couldn't found.");
        }
    }

    /*
     * Taken from http://stackoverflow.com/a/1657250/689144
     */
    public static String toSlug(String input) {
        String nowhitespace = WHITESPACE.matcher(input).replaceAll("_");
        String normalized = Normalizer.normalize(nowhitespace, Normalizer.Form.NFD);
        String slug = NONLATIN.matcher(normalized).replaceAll("");
        return slug.toLowerCase(Locale.ENGLISH);
    }
}
