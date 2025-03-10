package com.databricks.labs.mosaic.functions

import com.databricks.labs.mosaic._
import com.databricks.labs.mosaic.core.crs.CRSBoundsProvider
import com.databricks.labs.mosaic.core.geometry.api.GeometryAPI
import com.databricks.labs.mosaic.core.index.IndexSystem
import com.databricks.labs.mosaic.core.raster.api.RasterAPI
import com.databricks.labs.mosaic.core.types.ChipType
import com.databricks.labs.mosaic.datasource.multiread.MosaicDataFrameReader
import com.databricks.labs.mosaic.expressions.constructors._
import com.databricks.labs.mosaic.expressions.format._
import com.databricks.labs.mosaic.expressions.geometry._
import com.databricks.labs.mosaic.expressions.geometry.ST_MinMaxXYZ._
import com.databricks.labs.mosaic.expressions.index._
import com.databricks.labs.mosaic.expressions.raster._
import com.databricks.labs.mosaic.expressions.util.TrySql
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{Column, SparkSession}
import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry
import org.apache.spark.sql.catalyst.expressions.{Expression, Literal}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{LongType, StringType}

import scala.reflect.runtime.universe

//noinspection DuplicatedCode
class MosaicContext(indexSystem: IndexSystem, geometryAPI: GeometryAPI, rasterAPI: RasterAPI) extends Serializable with Logging {

    // Make spark aware of the mosaic setup
    // Check the DBR type and raise appropriate warnings
    private val spark = SparkSession.builder().getOrCreate()

    val crsBoundsProvider: CRSBoundsProvider = CRSBoundsProvider(geometryAPI)
    MosaicContext.checkDBR(spark)

    spark.conf.set(MOSAIC_INDEX_SYSTEM, indexSystem.name)
    spark.conf.set(MOSAIC_GEOMETRY_API, geometryAPI.name)
    spark.conf.set(MOSAIC_RASTER_API, rasterAPI.name)

    import org.apache.spark.sql.adapters.{Column => ColumnAdapter}
    val mirror: universe.Mirror = universe.runtimeMirror(getClass.getClassLoader)
    val expressionConfig: MosaicExpressionConfig = MosaicExpressionConfig(spark)


    def setCellIdDataType(dataType: String): Unit =
        if (dataType == "string") {
            indexSystem.setCellIdDataType(StringType)
        } else if (dataType == "long") {
            indexSystem.setCellIdDataType(LongType)
        } else {
            throw new Error(s"Unsupported data type: $dataType")
        }

    def registerProductH3(registry: FunctionRegistry, dbName: Option[String]): Unit = {
        aliasFunction(registry, "grid_longlatascellid", dbName, "h3_longlatash3", None)
        aliasFunction(registry, "grid_polyfill", dbName, "h3_polyfillash3", None)
        aliasFunction(registry, "grid_boundaryaswkb", dbName, "h3_boundaryaswkb", None)
        aliasFunction(registry, "grid_distance", dbName, "h3_distance", None)
    }

    def aliasFunction(
        registry: FunctionRegistry,
        alias: String,
        aliasDbName: Option[String],
        functionName: String,
        functionDbName: Option[String]
    ): Unit = {
        registry.registerFunction(
          FunctionIdentifier(alias, aliasDbName),
          registry.lookupFunction(FunctionIdentifier(functionName, functionDbName)).get,
          registry.lookupFunctionBuilder(FunctionIdentifier(functionName, functionDbName)).get
        )
    }

    def register(): Unit = {
        val spark = SparkSession.builder().getOrCreate()
        register(spark)
    }

    def register(database: String): Unit = {
        val spark = SparkSession.builder().getOrCreate()
        spark.sql(s"create database if not exists $database")
        register(spark, Some(database))
    }

    /**
      * Registers required parsers for SQL for Mosaic functionality.
      *
      * @param spark
      *   SparkSession to which the parsers are registered to.
      * @param database
      *   A database to which functions are added to. By default none is passed
      *   resulting in functions being registered in default database.
      */
    // noinspection ZeroIndexToHead
    // scalastyle:off line.size.limit
    def register(
        spark: SparkSession,
        database: Option[String] = None
    ): Unit = {
        val registry = spark.sessionState.functionRegistry
        val mosaicRegistry = MosaicRegistry(registry, database)

        /** IndexSystem and GeometryAPI Agnostic methods */
        registry.registerFunction(
          FunctionIdentifier("as_hex", database),
          AsHex.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => AsHex(exprs(0))
        )
        registry.registerFunction(
          FunctionIdentifier("as_json", database),
          AsJSON.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => AsJSON(exprs(0))
        )
        registry.registerFunction(
          FunctionIdentifier("st_point", database),
          ST_Point.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => ST_Point(exprs(0), exprs(1))
        )
        registry.registerFunction(
          FunctionIdentifier("st_makeline", database),
          ST_MakeLine.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => ST_MakeLine(exprs(0), geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("st_polygon", database),
          ST_MakePolygon.registryExpressionInfo(database),
          (exprs: Seq[Expression]) =>
              exprs match {
                  case e if e.length == 1 => ST_MakePolygon(e.head, array().expr)
                  case e if e.length == 2 => ST_MakePolygon(e.head, e.last)
                  case _                  => throw new Error("Wrong number of arguments.")
              }
        )

        /** GeometryAPI Specific */
        registry.registerFunction(
          FunctionIdentifier("flatten_polygons", database),
          FlattenPolygons.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => FlattenPolygons(exprs(0), geometryAPI.name)
        )

        mosaicRegistry.registerExpression[ST_Area](expressionConfig)
        mosaicRegistry.registerExpression[ST_Buffer](expressionConfig)
        mosaicRegistry.registerExpression[ST_BufferLoop](expressionConfig)
        mosaicRegistry.registerExpression[ST_Centroid](expressionConfig)
        mosaicRegistry.registerExpression[ST_Contains](expressionConfig)
        mosaicRegistry.registerExpression[ST_ConvexHull](expressionConfig)
        mosaicRegistry.registerExpression[ST_Distance](expressionConfig)
        mosaicRegistry.registerExpression[ST_Difference](expressionConfig)
        mosaicRegistry.registerExpression[ST_Buffer](expressionConfig)
        mosaicRegistry.registerExpression[ST_Envelope](expressionConfig)
        mosaicRegistry.registerExpression[ST_GeometryType](expressionConfig)
        mosaicRegistry.registerExpression[ST_HasValidCoordinates](expressionConfig)
        mosaicRegistry.registerExpression[ST_Intersection](expressionConfig)
        mosaicRegistry.registerExpression[ST_Intersects](expressionConfig)
        mosaicRegistry.registerExpression[ST_IsValid](expressionConfig)
        mosaicRegistry.registerExpression[ST_Length](expressionConfig)
        mosaicRegistry.registerExpression[ST_Length]("st_perimeter", expressionConfig)
        mosaicRegistry.registerExpression[ST_XMin](expressionConfig)
        mosaicRegistry.registerExpression[ST_XMax](expressionConfig)
        mosaicRegistry.registerExpression[ST_YMin](expressionConfig)
        mosaicRegistry.registerExpression[ST_YMax](expressionConfig)
        mosaicRegistry.registerExpression[ST_ZMin](expressionConfig)
        mosaicRegistry.registerExpression[ST_ZMax](expressionConfig)
        mosaicRegistry.registerExpression[ST_NumPoints](expressionConfig)
        mosaicRegistry.registerExpression[ST_Rotate](expressionConfig)
        mosaicRegistry.registerExpression[ST_Scale](expressionConfig)
        mosaicRegistry.registerExpression[ST_SetSRID](expressionConfig)
        mosaicRegistry.registerExpression[ST_Simplify](expressionConfig)
        mosaicRegistry.registerExpression[ST_SRID](expressionConfig)
        mosaicRegistry.registerExpression[ST_Translate](expressionConfig)
        mosaicRegistry.registerExpression[ST_Transform](expressionConfig)
        mosaicRegistry.registerExpression[ST_UnaryUnion](expressionConfig)
        mosaicRegistry.registerExpression[ST_Union](expressionConfig)
        mosaicRegistry.registerExpression[ST_UpdateSRID](expressionConfig)
        mosaicRegistry.registerExpression[ST_X](expressionConfig)
        mosaicRegistry.registerExpression[ST_Y](expressionConfig)
        mosaicRegistry.registerExpression[ST_Haversine](expressionConfig)

        registry.registerFunction(
          FunctionIdentifier("st_centroid2D", database),
          ST_Centroid.legacyInfo(database, "st_centroid2D"),
          (exprs: Seq[Expression]) => functions.st_centroid2D(ColumnAdapter(exprs(0))).expr
        )

        registry.registerFunction(
          FunctionIdentifier("st_geomfromwkt", database),
          ConvertTo.registryExpressionInfo(database, "st_geomfromwkt"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "coords", geometryAPI.name, Some("st_geomfromwkt"))
        )
        registry.registerFunction(
          FunctionIdentifier("st_geomfromwkb", database),
          ConvertTo.registryExpressionInfo(database, "st_geomfromwkb"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "coords", geometryAPI.name, Some("st_geomfromwkb"))
        )
        registry.registerFunction(
          FunctionIdentifier("st_geomfromgeojson", database),
          ConvertTo.registryExpressionInfo(database, "st_geomfromgeojson"),
          (exprs: Seq[Expression]) => ConvertTo(AsJSON(exprs(0)), "coords", geometryAPI.name, Some("st_geomfromgeojson"))
        )
        registry.registerFunction(
          FunctionIdentifier("convert_to_hex", database),
          ConvertTo.registryExpressionInfo(database, "convert_to_hex"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "hex", geometryAPI.name, Some("convert_to_hex"))
        )
        registry.registerFunction(
          FunctionIdentifier("convert_to_wkt", database),
          ConvertTo.registryExpressionInfo(database, "convert_to_wkt"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "wkt", geometryAPI.name, Some("convert_to_wkt"))
        )
        registry.registerFunction(
          FunctionIdentifier("convert_to_wkb", database),
          ConvertTo.registryExpressionInfo(database, "convert_to_wkb"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "wkb", geometryAPI.name, Some("convert_to_wkb"))
        )
        registry.registerFunction(
          FunctionIdentifier("convert_to_coords", database),
          ConvertTo.registryExpressionInfo(database, "convert_to_coords"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "coords", geometryAPI.name, Some("convert_to_coords"))
        )
        registry.registerFunction(
          FunctionIdentifier("convert_to_geojson", database),
          ConvertTo.registryExpressionInfo(database, "convert_to_geojson"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "geojson", geometryAPI.name, Some("convert_to_geojson"))
        )
        registry.registerFunction(
          FunctionIdentifier("st_aswkt", database),
          ConvertTo.registryExpressionInfo(database, "st_aswkt"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "wkt", geometryAPI.name, Some("st_aswkt"))
        )
        registry.registerFunction(
          FunctionIdentifier("st_astext", database),
          ConvertTo.registryExpressionInfo(database, "st_astext"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "wkt", geometryAPI.name, Some("st_astext"))
        )
        registry.registerFunction(
          FunctionIdentifier("st_aswkb", database),
          ConvertTo.registryExpressionInfo(database, "st_aswkb"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "wkb", geometryAPI.name, Some("st_aswkb"))
        )
        registry.registerFunction(
          FunctionIdentifier("st_asbinary", database),
          ConvertTo.registryExpressionInfo(database, "st_asbinary"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "wkb", geometryAPI.name, Some("st_asbinary"))
        )
        registry.registerFunction(
          FunctionIdentifier("st_asgeojson", database),
          ConvertTo.registryExpressionInfo(database, "st_asgeojson"),
          (exprs: Seq[Expression]) => ConvertTo(exprs(0), "geojson", geometryAPI.name, Some("st_asgeojson"))
        )

        /** RasterAPI dependent functions */
        mosaicRegistry.registerExpression[RST_BandMetaData](expressionConfig)
        mosaicRegistry.registerExpression[RST_GeoReference](expressionConfig)
        mosaicRegistry.registerExpression[RST_IsEmpty](expressionConfig)
        mosaicRegistry.registerExpression[RST_MemSize](expressionConfig)
        mosaicRegistry.registerExpression[RST_MetaData](expressionConfig)
        mosaicRegistry.registerExpression[RST_NumBands](expressionConfig)
        mosaicRegistry.registerExpression[RST_PixelWidth](expressionConfig)
        mosaicRegistry.registerExpression[RST_PixelHeight](expressionConfig)
        mosaicRegistry.registerExpression[RST_RasterToGridAvg](expressionConfig)
        mosaicRegistry.registerExpression[RST_RasterToGridMax](expressionConfig)
        mosaicRegistry.registerExpression[RST_RasterToGridMin](expressionConfig)
        mosaicRegistry.registerExpression[RST_RasterToGridMedian](expressionConfig)
        mosaicRegistry.registerExpression[RST_RasterToGridCount](expressionConfig)
        mosaicRegistry.registerExpression[RST_RasterToWorldCoord](expressionConfig)
        mosaicRegistry.registerExpression[RST_RasterToWorldCoordX](expressionConfig)
        mosaicRegistry.registerExpression[RST_RasterToWorldCoordY](expressionConfig)
        mosaicRegistry.registerExpression[RST_ReTile](expressionConfig)
        mosaicRegistry.registerExpression[RST_Rotation](expressionConfig)
        mosaicRegistry.registerExpression[RST_ScaleX](expressionConfig)
        mosaicRegistry.registerExpression[RST_ScaleY](expressionConfig)
        mosaicRegistry.registerExpression[RST_SkewX](expressionConfig)
        mosaicRegistry.registerExpression[RST_SkewY](expressionConfig)
        mosaicRegistry.registerExpression[RST_SRID](expressionConfig)
        mosaicRegistry.registerExpression[RST_Subdatasets](expressionConfig)
        mosaicRegistry.registerExpression[RST_Summary](expressionConfig)
        mosaicRegistry.registerExpression[RST_UpperLeftX](expressionConfig)
        mosaicRegistry.registerExpression[RST_UpperLeftY](expressionConfig)
        mosaicRegistry.registerExpression[RST_Width](expressionConfig)
        mosaicRegistry.registerExpression[RST_Height](expressionConfig)
        mosaicRegistry.registerExpression[RST_WorldToRasterCoord](expressionConfig)
        mosaicRegistry.registerExpression[RST_WorldToRasterCoordX](expressionConfig)
        mosaicRegistry.registerExpression[RST_WorldToRasterCoordY](expressionConfig)

        /** Aggregators */
        registry.registerFunction(
          FunctionIdentifier("st_intersection_aggregate", database),
          ST_IntersectionAggregate.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => ST_IntersectionAggregate(exprs(0), exprs(1), geometryAPI.name, indexSystem, 0, 0)
        )
        registry.registerFunction(
          FunctionIdentifier("st_intersects_aggregate", database),
          ST_IntersectsAggregate.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => ST_IntersectsAggregate(exprs(0), exprs(1), geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("st_union_agg", database),
          ST_UnionAgg.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => ST_UnionAgg(exprs(0), geometryAPI.name)
        )

        /** IndexSystem and GeometryAPI Specific methods */
        registry.registerFunction(
          FunctionIdentifier("grid_tessellateexplode", database),
          MosaicExplode.registryExpressionInfo(database),
          (exprs: Seq[Expression]) =>
              exprs match {
                  case e if e.length == 2 => MosaicExplode(e(0), e(1), lit(true).expr, indexSystem, geometryAPI.name)
                  case e                  => MosaicExplode(e(0), e(1), e(2), indexSystem, geometryAPI.name)
              }
        )
        registry.registerFunction(
          FunctionIdentifier("grid_tessellateaslong", database),
          MosaicFill.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => MosaicFill(exprs(0), exprs(1), lit(true).expr, indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_tessellate", database),
          MosaicFill.registryExpressionInfo(database),
          (exprs: Seq[Expression]) =>
              exprs match {
                  case e if e.length == 2 => MosaicFill(e(0), e(1), lit(true).expr, indexSystem, geometryAPI.name)
                  case e                  => MosaicFill(e(0), e(1), e(2), indexSystem, geometryAPI.name)
              }
        )

        if (shouldUseDatabricksH3()) {
            // Forward the H3 calls to product directly
            registerProductH3(registry, database)
        } else {
            registry.registerFunction(
              FunctionIdentifier("grid_longlatascellid", database),
              PointIndexLonLat.registryExpressionInfo(database),
              (exprs: Seq[Expression]) => PointIndexLonLat(exprs(0), exprs(1), exprs(2), indexSystem)
            )

            registry.registerFunction(
              FunctionIdentifier("grid_polyfill", database),
              Polyfill.registryExpressionInfo(database),
              (exprs: Seq[Expression]) => Polyfill(exprs(0), exprs(1), indexSystem, geometryAPI.name)
            )

            registry.registerFunction(
              FunctionIdentifier("grid_boundaryaswkb", database),
              IndexGeometry.registryExpressionInfo(database),
              (exprs: Seq[Expression]) => IndexGeometry(exprs(0), Literal("WKB"), indexSystem, geometryAPI.name)
            )

            registry.registerFunction(
              FunctionIdentifier("grid_distance", database),
              GridDistance.registryExpressionInfo(database),
              (exprs: Seq[Expression]) => GridDistance(exprs(0), exprs(1), indexSystem, geometryAPI.name)
            )
        }

        registry.registerFunction(
          FunctionIdentifier("grid_pointascellid", database),
          PointIndexGeom.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => PointIndexGeom(exprs(0), exprs(1), indexSystem, geometryAPI.name)
        )

        registry.registerFunction(
          FunctionIdentifier("grid_cell_intersection", database),
          CellIntersection.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => CellIntersection(exprs(0), exprs(1), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_cell_union", database),
          CellUnion.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => CellUnion(exprs(0), exprs(1), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_cell_intersection_agg", database),
          CellIntersectionAgg.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => CellIntersectionAgg(exprs(0), geometryAPI.name, indexSystem)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_cell_union_agg", database),
          CellUnionAgg.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => CellUnionAgg(exprs(0), geometryAPI.name, indexSystem)
        )

        registry.registerFunction(
          FunctionIdentifier("grid_boundary", database),
          IndexGeometry.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => IndexGeometry(exprs(0), exprs(1), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_cellkring", database),
          CellKRing.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => CellKRing(exprs(0), exprs(1), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_cellkringexplode", database),
          CellKRingExplode.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => CellKRingExplode(exprs(0), exprs(1), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_cellarea", database),
          CellArea.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => CellArea(exprs(0), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_cellkloop", database),
          CellKLoop.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => CellKLoop(exprs(0), exprs(1), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_cellkloopexplode", database),
          CellKLoopExplode.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => CellKLoopExplode(exprs(0), exprs(1), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_geometrykring", database),
          GeometryKRing.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => GeometryKRing(exprs(0), exprs(1), exprs(2), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_geometrykringexplode", database),
          GeometryKRingExplode.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => GeometryKRingExplode(exprs(0), exprs(1), exprs(2), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_geometrykloop", database),
          GeometryKLoop.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => GeometryKLoop(exprs(0), exprs(1), exprs(2), indexSystem, geometryAPI.name)
        )
        registry.registerFunction(
          FunctionIdentifier("grid_geometrykloopexplode", database),
          GeometryKLoopExplode.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => GeometryKLoopExplode(exprs(0), exprs(1), exprs(2), indexSystem, geometryAPI.name)
        )

        // DataType keywords are needed at checkInput execution time.
        // They cant be passed as Expressions to ConvertTo Expression.
        // Instead they are passed as String instances and for SQL
        // parser purposes separate method names are defined.

        registry.registerFunction(
          FunctionIdentifier("st_dump", database),
          FlattenPolygons.registryExpressionInfo(database),
          (exprs: Seq[Expression]) => FlattenPolygons(exprs(0), geometryAPI.name)
        )

        // Not specific to Mosaic
        registry.registerFunction(
          FunctionIdentifier("try_sql", database),
          TrySql.registryExpressionInfo(database, "try_sql"),
          (exprs: Seq[Expression]) => TrySql(exprs(0))
        )

        /** Legacy API Specific aliases */
        aliasFunction(registry, "index_geometry", database, "grid_boundaryaswkb", database)
        aliasFunction(registry, "mosaic_explode", database, "grid_tessellateexplode", database)
        aliasFunction(registry, "mosaicfill", database, "grid_tessellate", database)
        aliasFunction(registry, "point_index_geom", database, "grid_pointascellid", database)
        aliasFunction(registry, "point_index_lonlat", database, "grid_longlatascellid", database)
        aliasFunction(registry, "polyfill", database, "grid_polyfill", database)

    }

    def getGeometryAPI: GeometryAPI = this.geometryAPI

    def getRasterAPI: RasterAPI = this.rasterAPI

    def getIndexSystem: IndexSystem = this.indexSystem

    def getProductMethod(methodName: String): universe.MethodMirror = {
        val functionsModuleSymbol: universe.ModuleSymbol = mirror.staticModule(DATABRICKS_SQL_FUNCTIONS_MODULE)

        val functionsModuleMirror = mirror.reflectModule(functionsModuleSymbol)
        val instanceMirror = mirror.reflect(functionsModuleMirror.instance)

        val methodSymbol = functionsModuleSymbol.info.decl(universe.TermName(methodName)).asMethod
        instanceMirror.reflectMethod(methodSymbol)
    }

    def shouldUseDatabricksH3(): Boolean = {
        val spark = SparkSession.builder().getOrCreate()
        val isDatabricksH3Enabled = spark.conf.get(SPARK_DATABRICKS_GEO_H3_ENABLED, "false") == "true"
        indexSystem.name == H3.name && isDatabricksH3Enabled
    }

    // scalastyle:off object.name
    object functions extends Serializable {

        /**
          * functions should follow the pattern "def fname(argName: Type, ...):
          * returnType = ..." failing to do so may brake the R build.
          */

        /** IndexSystem and GeometryAPI Agnostic methods */
        def as_hex(inGeom: Column): Column = ColumnAdapter(AsHex(inGeom.expr))
        def as_json(inGeom: Column): Column = ColumnAdapter(AsJSON(inGeom.expr))

        /** GeometryAPI Specific */

        /** Spatial functions */
        def flatten_polygons(geom: Column): Column = ColumnAdapter(FlattenPolygons(geom.expr, geometryAPI.name))
        def st_area(geom: Column): Column = ColumnAdapter(ST_Area(geom.expr, expressionConfig))
        def st_buffer(geom: Column, radius: Column): Column =
            ColumnAdapter(ST_Buffer(geom.expr, radius.cast("double").expr, expressionConfig))
        def st_buffer(geom: Column, radius: Double): Column =
            ColumnAdapter(ST_Buffer(geom.expr, lit(radius).cast("double").expr, expressionConfig))
        def st_bufferloop(geom: Column, r1: Column, r2: Column): Column =
            ColumnAdapter(ST_BufferLoop(geom.expr, r1.cast("double").expr, r2.cast("double").expr, expressionConfig))
        def st_bufferloop(geom: Column, r1: Double, r2: Double): Column =
            ColumnAdapter(ST_BufferLoop(geom.expr, lit(r1).cast("double").expr, lit(r2).cast("double").expr, expressionConfig))
        def st_centroid(geom: Column): Column = ColumnAdapter(ST_Centroid(geom.expr, expressionConfig))
        def st_convexhull(geom: Column): Column = ColumnAdapter(ST_ConvexHull(geom.expr, expressionConfig))
        def st_difference(geom1: Column, geom2: Column): Column = ColumnAdapter(ST_Difference(geom1.expr, geom2.expr, expressionConfig))
        def st_distance(geom1: Column, geom2: Column): Column = ColumnAdapter(ST_Distance(geom1.expr, geom2.expr, expressionConfig))
        def st_dump(geom: Column): Column = ColumnAdapter(FlattenPolygons(geom.expr, geometryAPI.name))
        def st_envelope(geom: Column): Column = ColumnAdapter(ST_Envelope(geom.expr, expressionConfig))
        def st_geometrytype(geom: Column): Column = ColumnAdapter(ST_GeometryType(geom.expr, expressionConfig))
        def st_hasvalidcoordinates(geom: Column, crsCode: Column, which: Column): Column =
            ColumnAdapter(ST_HasValidCoordinates(geom.expr, crsCode.expr, which.expr, expressionConfig))
        def st_intersection(left: Column, right: Column): Column = ColumnAdapter(ST_Intersection(left.expr, right.expr, expressionConfig))
        def st_isvalid(geom: Column): Column = ColumnAdapter(ST_IsValid(geom.expr, expressionConfig))
        def st_length(geom: Column): Column = ColumnAdapter(ST_Length(geom.expr, expressionConfig))
        def st_numpoints(geom: Column): Column = ColumnAdapter(ST_NumPoints(geom.expr, expressionConfig))
        def st_perimeter(geom: Column): Column = ColumnAdapter(ST_Length(geom.expr, expressionConfig))

        def st_haversine(lat1: Column, lon1: Column, lat2: Column, lon2: Column): Column =
            ColumnAdapter(ST_Haversine(lat1.expr, lon1.expr, lat2.expr, lon2.expr))

        def st_rotate(geom1: Column, td: Column): Column = ColumnAdapter(ST_Rotate(geom1.expr, td.expr, expressionConfig))
        def st_scale(geom1: Column, xd: Column, yd: Column): Column =
            ColumnAdapter(ST_Scale(geom1.expr, xd.expr, yd.expr, expressionConfig))
        def st_setsrid(geom: Column, srid: Column): Column = ColumnAdapter(ST_SetSRID(geom.expr, srid.expr, expressionConfig))
        def st_simplify(geom: Column, tolerance: Column): Column =
            ColumnAdapter(ST_Simplify(geom.expr, tolerance.cast("double").expr, expressionConfig))
        def st_simplify(geom: Column, tolerance: Double): Column =
            ColumnAdapter(ST_Simplify(geom.expr, lit(tolerance).cast("double").expr, expressionConfig))
        def st_srid(geom: Column): Column = ColumnAdapter(ST_SRID(geom.expr, expressionConfig))
        def st_transform(geom: Column, srid: Column): Column = ColumnAdapter(ST_Transform(geom.expr, srid.expr, expressionConfig))
        def st_translate(geom1: Column, xd: Column, yd: Column): Column =
            ColumnAdapter(ST_Translate(geom1.expr, xd.expr, yd.expr, expressionConfig))
        def st_x(geom: Column): Column = ColumnAdapter(ST_X(geom.expr, expressionConfig))
        def st_y(geom: Column): Column = ColumnAdapter(ST_Y(geom.expr, expressionConfig))
        def st_xmax(geom: Column): Column = ColumnAdapter(ST_MinMaxXYZ(geom.expr, expressionConfig, "X", "MAX"))
        def st_xmin(geom: Column): Column = ColumnAdapter(ST_MinMaxXYZ(geom.expr, expressionConfig, "X", "MIN"))
        def st_ymax(geom: Column): Column = ColumnAdapter(ST_MinMaxXYZ(geom.expr, expressionConfig, "Y", "MAX"))
        def st_ymin(geom: Column): Column = ColumnAdapter(ST_MinMaxXYZ(geom.expr, expressionConfig, "Y", "MIN"))
        def st_zmax(geom: Column): Column = ColumnAdapter(ST_MinMaxXYZ(geom.expr, expressionConfig, "Z", "MAX"))
        def st_zmin(geom: Column): Column = ColumnAdapter(ST_MinMaxXYZ(geom.expr, expressionConfig, "Z", "MIN"))
        def st_union(leftGeom: Column, rightGeom: Column): Column = ColumnAdapter(ST_Union(leftGeom.expr, rightGeom.expr, expressionConfig))
        def st_unaryunion(geom: Column): Column = ColumnAdapter(ST_UnaryUnion(geom.expr, expressionConfig))
        def st_updatesrid(geom: Column, srcSRID: Column, destSRID: Column): Column =
            ColumnAdapter(ST_UpdateSRID(geom.expr, srcSRID.cast("int").expr, destSRID.cast("int").expr, expressionConfig))
        def st_updatesrid(geom: Column, srcSRID: Int, destSRID: Int): Column =
            ColumnAdapter(ST_UpdateSRID(geom.expr, lit(srcSRID).expr, lit(destSRID).expr, expressionConfig))

        /** Undocumented helper */
        def convert_to(inGeom: Column, outDataType: String): Column =
            ColumnAdapter(ConvertTo(inGeom.expr, outDataType, geometryAPI.name, Some("convert_to")))

        /** Geometry constructors */
        def st_point(xVal: Column, yVal: Column): Column = ColumnAdapter(ST_Point(xVal.expr, yVal.expr))
        def st_geomfromwkt(inGeom: Column): Column =
            ColumnAdapter(ConvertTo(inGeom.expr, "coords", geometryAPI.name, Some("st_geomfromwkt")))
        def st_geomfromwkb(inGeom: Column): Column =
            ColumnAdapter(ConvertTo(inGeom.expr, "coords", geometryAPI.name, Some("st_geomfromwkb")))
        def st_geomfromgeojson(inGeom: Column): Column =
            ColumnAdapter(ConvertTo(AsJSON(inGeom.expr), "coords", geometryAPI.name, Some("st_geomfromgeojson")))
        def st_makeline(points: Column): Column = ColumnAdapter(ST_MakeLine(points.expr, geometryAPI.name))
        def st_makepolygon(boundaryRing: Column): Column = ColumnAdapter(ST_MakePolygon(boundaryRing.expr, array().expr))
        def st_makepolygon(boundaryRing: Column, holeRingArray: Column): Column =
            ColumnAdapter(ST_MakePolygon(boundaryRing.expr, holeRingArray.expr))

        /** Geometry accessors */
        def st_asbinary(geom: Column): Column = ColumnAdapter(ConvertTo(geom.expr, "wkb", geometryAPI.name, Some("st_asbinary")))
        def st_asgeojson(geom: Column): Column = ColumnAdapter(ConvertTo(geom.expr, "geojson", geometryAPI.name, Some("st_asgeojson")))
        def st_astext(geom: Column): Column = ColumnAdapter(ConvertTo(geom.expr, "wkt", geometryAPI.name, Some("st_astext")))
        def st_aswkb(geom: Column): Column = ColumnAdapter(ConvertTo(geom.expr, "wkb", geometryAPI.name, Some("st_aswkb")))
        def st_aswkt(geom: Column): Column = ColumnAdapter(ConvertTo(geom.expr, "wkt", geometryAPI.name, Some("st_aswkt")))

        /** Spatial predicates */
        def st_contains(geom1: Column, geom2: Column): Column = ColumnAdapter(ST_Contains(geom1.expr, geom2.expr, expressionConfig))
        def st_intersects(left: Column, right: Column): Column = ColumnAdapter(ST_Intersects(left.expr, right.expr, expressionConfig))

        /** RasterAPI dependent functions */
        def rst_bandmetadata(raster: Column, band: Column): Column =
            ColumnAdapter(RST_BandMetaData(raster.expr, band.expr, expressionConfig))
        def rst_bandmetadata(raster: Column, band: Int): Column =
            ColumnAdapter(RST_BandMetaData(raster.expr, lit(band).expr, expressionConfig))
        def rst_bandmetadata(raster: String, band: Int): Column =
            ColumnAdapter(RST_BandMetaData(lit(raster).expr, lit(band).expr, expressionConfig))
        def rst_georeference(raster: Column): Column = ColumnAdapter(RST_GeoReference(raster.expr, expressionConfig))
        def rst_georeference(raster: String): Column = ColumnAdapter(RST_GeoReference(lit(raster).expr, expressionConfig))
        def rst_height(raster: Column): Column = ColumnAdapter(RST_Height(raster.expr, expressionConfig))
        def rst_height(raster: String): Column = ColumnAdapter(RST_Height(lit(raster).expr, expressionConfig))
        def rst_isempty(raster: Column): Column = ColumnAdapter(RST_IsEmpty(raster.expr, expressionConfig))
        def rst_isempty(raster: String): Column = ColumnAdapter(RST_IsEmpty(lit(raster).expr, expressionConfig))
        def rst_memsize(raster: Column): Column = ColumnAdapter(RST_MemSize(raster.expr, expressionConfig))
        def rst_memsize(raster: String): Column = ColumnAdapter(RST_MemSize(lit(raster).expr, expressionConfig))
        def rst_metadata(raster: Column): Column = ColumnAdapter(RST_MetaData(raster.expr, expressionConfig))
        def rst_metadata(raster: String): Column = ColumnAdapter(RST_MetaData(lit(raster).expr, expressionConfig))
        def rst_numbands(raster: Column): Column = ColumnAdapter(RST_NumBands(raster.expr, expressionConfig))
        def rst_numbands(raster: String): Column = ColumnAdapter(RST_NumBands(lit(raster).expr, expressionConfig))
        def rst_pixelheight(raster: Column): Column = ColumnAdapter(RST_PixelHeight(raster.expr, expressionConfig))
        def rst_pixelheight(raster: String): Column = ColumnAdapter(RST_PixelHeight(lit(raster).expr, expressionConfig))
        def rst_pixelwidth(raster: Column): Column = ColumnAdapter(RST_PixelWidth(raster.expr, expressionConfig))
        def rst_pixelwidth(raster: String): Column = ColumnAdapter(RST_PixelWidth(lit(raster).expr, expressionConfig))
        def rst_rastertogridavg(raster: Column, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridAvg(raster.expr, resolution.expr, expressionConfig))
        def rst_rastertogridavg(raster: String, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridAvg(lit(raster).expr, resolution.expr, expressionConfig))
        def rst_rastertogridcount(raster: Column, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridCount(raster.expr, resolution.expr, expressionConfig))
        def rst_rastertogridcount(raster: String, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridCount(lit(raster).expr, resolution.expr, expressionConfig))
        def rst_rastertogridmax(raster: Column, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridMax(raster.expr, resolution.expr, expressionConfig))
        def rst_rastertogridmax(raster: String, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridMax(lit(raster).expr, resolution.expr, expressionConfig))
        def rst_rastertogridmedian(raster: Column, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridMedian(raster.expr, resolution.expr, expressionConfig))
        def rst_rastertogridmedian(raster: String, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridMedian(lit(raster).expr, resolution.expr, expressionConfig))
        def rst_rastertogridmin(raster: Column, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridMin(raster.expr, resolution.expr, expressionConfig))
        def rst_rastertogridmin(raster: String, resolution: Column): Column =
            ColumnAdapter(RST_RasterToGridMin(lit(raster).expr, resolution.expr, expressionConfig))
        def rst_rastertoworldcoord(raster: Column, x: Column, y: Column): Column =
            ColumnAdapter(RST_RasterToWorldCoord(raster.expr, x.expr, y.expr, expressionConfig))
        def rst_rastertoworldcoord(raster: String, x: Column, y: Column): Column =
            ColumnAdapter(RST_RasterToWorldCoord(lit(raster).expr, x.expr, y.expr, expressionConfig))
        def rst_rastertoworldcoord(raster: Column, x: Int, y: Int): Column =
            ColumnAdapter(RST_RasterToWorldCoord(raster.expr, lit(x).expr, lit(y).expr, expressionConfig))
        def rst_rastertoworldcoordx(raster: Column, x: Column, y: Column): Column =
            ColumnAdapter(RST_RasterToWorldCoordX(raster.expr, x.expr, y.expr, expressionConfig))
        def rst_rastertoworldcoordx(raster: String, x: Column, y: Column): Column =
            ColumnAdapter(RST_RasterToWorldCoordX(lit(raster).expr, x.expr, y.expr, expressionConfig))
        def rst_rastertoworldcoordx(raster: Column, x: Int, y: Int): Column =
            ColumnAdapter(RST_RasterToWorldCoordX(raster.expr, lit(x).expr, lit(y).expr, expressionConfig))
        def rst_rastertoworldcoordy(raster: Column, x: Column, y: Column): Column =
            ColumnAdapter(RST_RasterToWorldCoordY(raster.expr, x.expr, y.expr, expressionConfig))
        def rst_rastertoworldcoordy(raster: String, x: Column, y: Column): Column =
            ColumnAdapter(RST_RasterToWorldCoordY(lit(raster).expr, x.expr, y.expr, expressionConfig))
        def rst_rastertoworldcoordy(raster: Column, x: Int, y: Int): Column =
            ColumnAdapter(RST_RasterToWorldCoordY(raster.expr, lit(x).expr, lit(y).expr, expressionConfig))
        def rst_retile(raster: Column, tileWidth: Column, tileHeight: Column): Column =
            ColumnAdapter(RST_ReTile(raster.expr, tileWidth.expr, tileHeight.expr, expressionConfig))
        def rst_retile(raster: Column, tileWidth: Int, tileHeight: Int): Column =
            ColumnAdapter(RST_ReTile(raster.expr, lit(tileWidth).expr, lit(tileHeight).expr, expressionConfig))
        def rst_retile(raster: String, tileWidth: Int, tileHeight: Int): Column =
            ColumnAdapter(RST_ReTile(lit(raster).expr, lit(tileWidth).expr, lit(tileHeight).expr, expressionConfig))
        def rst_rotation(raster: Column): Column = ColumnAdapter(RST_Rotation(raster.expr, expressionConfig))
        def rst_rotation(raster: String): Column = ColumnAdapter(RST_Rotation(lit(raster).expr, expressionConfig))
        def rst_scalex(raster: Column): Column = ColumnAdapter(RST_ScaleX(raster.expr, expressionConfig))
        def rst_scalex(raster: String): Column = ColumnAdapter(RST_ScaleX(lit(raster).expr, expressionConfig))
        def rst_scaley(raster: Column): Column = ColumnAdapter(RST_ScaleY(raster.expr, expressionConfig))
        def rst_scaley(raster: String): Column = ColumnAdapter(RST_ScaleY(lit(raster).expr, expressionConfig))
        def rst_skewx(raster: Column): Column = ColumnAdapter(RST_SkewX(raster.expr, expressionConfig))
        def rst_skewx(raster: String): Column = ColumnAdapter(RST_SkewX(lit(raster).expr, expressionConfig))
        def rst_skewy(raster: Column): Column = ColumnAdapter(RST_SkewY(raster.expr, expressionConfig))
        def rst_skewy(raster: String): Column = ColumnAdapter(RST_SkewY(lit(raster).expr, expressionConfig))
        def rst_srid(raster: Column): Column = ColumnAdapter(RST_SRID(raster.expr, expressionConfig))
        def rst_srid(raster: String): Column = ColumnAdapter(RST_SRID(lit(raster).expr, expressionConfig))
        def rst_subdatasets(raster: Column): Column = ColumnAdapter(RST_Subdatasets(raster.expr, expressionConfig))
        def rst_subdatasets(raster: String): Column = ColumnAdapter(RST_Subdatasets(lit(raster).expr, expressionConfig))
        def rst_summary(raster: Column): Column = ColumnAdapter(RST_Summary(raster.expr, expressionConfig))
        def rst_summary(raster: String): Column = ColumnAdapter(RST_Summary(lit(raster).expr, expressionConfig))
        def rst_upperleftx(raster: Column): Column = ColumnAdapter(RST_UpperLeftX(raster.expr, expressionConfig))
        def rst_upperleftx(raster: String): Column = ColumnAdapter(RST_UpperLeftX(lit(raster).expr, expressionConfig))
        def rst_upperlefty(raster: Column): Column = ColumnAdapter(RST_UpperLeftY(raster.expr, expressionConfig))
        def rst_upperlefty(raster: String): Column = ColumnAdapter(RST_UpperLeftY(lit(raster).expr, expressionConfig))
        def rst_width(raster: Column): Column = ColumnAdapter(RST_Width(raster.expr, expressionConfig))
        def rst_width(raster: String): Column = ColumnAdapter(RST_Width(lit(raster).expr, expressionConfig))
        def rst_worldtorastercoord(raster: Column, x: Column, y: Column): Column =
            ColumnAdapter(RST_WorldToRasterCoord(raster.expr, x.expr, y.expr, expressionConfig))
        def rst_worldtorastercoord(raster: Column, x: Double, y: Double): Column =
            ColumnAdapter(RST_WorldToRasterCoord(raster.expr, lit(x).expr, lit(y).expr, expressionConfig))
        def rst_worldtorastercoord(raster: String, x: Double, y: Double): Column =
            ColumnAdapter(RST_WorldToRasterCoord(lit(raster).expr, lit(x).expr, lit(y).expr, expressionConfig))
        def rst_worldtorastercoordx(raster: Column, x: Column, y: Column): Column =
            ColumnAdapter(RST_WorldToRasterCoordX(raster.expr, x.expr, y.expr, expressionConfig))
        def rst_worldtorastercoordx(raster: Column, x: Double, y: Double): Column =
            ColumnAdapter(RST_WorldToRasterCoordX(raster.expr, lit(x).expr, lit(y).expr, expressionConfig))
        def rst_worldtorastercoordx(raster: String, x: Double, y: Double): Column =
            ColumnAdapter(RST_WorldToRasterCoordX(lit(raster).expr, lit(x).expr, lit(y).expr, expressionConfig))
        def rst_worldtorastercoordy(raster: Column, x: Column, y: Column): Column =
            ColumnAdapter(RST_WorldToRasterCoordY(raster.expr, x.expr, y.expr, expressionConfig))
        def rst_worldtorastercoordy(raster: Column, x: Double, y: Double): Column =
            ColumnAdapter(RST_WorldToRasterCoordY(raster.expr, lit(x).expr, lit(y).expr, expressionConfig))
        def rst_worldtorastercoordy(raster: String, x: Double, y: Double): Column =
            ColumnAdapter(RST_WorldToRasterCoordY(lit(raster).expr, lit(x).expr, lit(y).expr, expressionConfig))

        /** Aggregators */
        def st_intersects_aggregate(leftIndex: Column, rightIndex: Column): Column =
            ColumnAdapter(
              ST_IntersectsAggregate(leftIndex.expr, rightIndex.expr, geometryAPI.name).toAggregateExpression(isDistinct = false)
            )
        def st_intersection_aggregate(leftIndex: Column, rightIndex: Column): Column =
            ColumnAdapter(
              ST_IntersectionAggregate(leftIndex.expr, rightIndex.expr, geometryAPI.name, indexSystem, 0, 0)
                  .toAggregateExpression(isDistinct = false)
            )
        def st_union_agg(geom: Column): Column =
            ColumnAdapter(ST_UnionAgg(geom.expr, geometryAPI.name).toAggregateExpression(isDistinct = false))

        /** IndexSystem Specific */

        /** IndexSystem and GeometryAPI Specific methods */
        def grid_cell_intersection(chip1: Column, chip2: Column): Column =
            ColumnAdapter(CellIntersection(chip1.expr, chip2.expr, indexSystem, geometryAPI.name))
        def grid_cell_intersection_agg(chip: Column): Column =
            ColumnAdapter(CellIntersectionAgg(chip.expr, geometryAPI.name, indexSystem).toAggregateExpression(isDistinct = false))
        def grid_cell_union(chip1: Column, chip2: Column): Column =
            ColumnAdapter(CellUnion(chip1.expr, chip2.expr, indexSystem, geometryAPI.name))
        def grid_cell_union_agg(chip: Column): Column =
            ColumnAdapter(CellUnionAgg(chip.expr, geometryAPI.name, indexSystem).toAggregateExpression(isDistinct = false))
        def grid_distance(cell1: Column, cell2: Column): Column =
            ColumnAdapter(GridDistance(cell1.expr, cell2.expr, indexSystem, geometryAPI.name))
        def grid_tessellateexplode(geom: Column, resolution: Column): Column = grid_tessellateexplode(geom, resolution, lit(true))
        def grid_tessellateexplode(geom: Column, resolution: Int): Column = grid_tessellateexplode(geom, lit(resolution), lit(true))
        def grid_tessellateexplode(geom: Column, resolution: Int, keepCoreGeometries: Boolean): Column =
            grid_tessellateexplode(geom, lit(resolution), lit(keepCoreGeometries))
        def grid_tessellateexplode(geom: Column, resolution: Int, keepCoreGeometries: Column): Column =
            grid_tessellateexplode(geom, lit(resolution), keepCoreGeometries)
        def grid_tessellateexplode(geom: Column, resolution: Column, keepCoreGeometries: Column): Column =
            ColumnAdapter(
              MosaicExplode(geom.expr, resolution.expr, keepCoreGeometries.expr, indexSystem, geometryAPI.name)
            )
        def grid_tessellate(geom: Column, resolution: Column): Column = grid_tessellate(geom, resolution, lit(true))
        def grid_tessellate(geom: Column, resolution: Int): Column = grid_tessellate(geom, lit(resolution), lit(true))
        def grid_tessellate(geom: Column, resolution: Column, keepCoreGeometries: Boolean): Column =
            grid_tessellate(geom, resolution, lit(keepCoreGeometries))
        def grid_tessellate(geom: Column, resolution: Int, keepCoreGeometries: Boolean): Column =
            grid_tessellate(geom, lit(resolution), lit(keepCoreGeometries))
        def grid_tessellate(geom: Column, resolution: Column, keepCoreGeometries: Column): Column =
            ColumnAdapter(
              MosaicFill(geom.expr, resolution.expr, keepCoreGeometries.expr, indexSystem, geometryAPI.name)
            )
        def grid_pointascellid(point: Column, resolution: Column): Column =
            ColumnAdapter(PointIndexGeom(point.expr, resolution.expr, indexSystem, geometryAPI.name))
        def grid_pointascellid(point: Column, resolution: Int): Column =
            ColumnAdapter(PointIndexGeom(point.expr, lit(resolution).expr, indexSystem, geometryAPI.name))
        def grid_longlatascellid(lon: Column, lat: Column, resolution: Column): Column = {
            if (shouldUseDatabricksH3()) {
                getProductMethod("h3_longlatascellid")
                    .apply(lon, lat, resolution)
                    .asInstanceOf[Column]
            } else {
                ColumnAdapter(PointIndexLonLat(lon.expr, lat.expr, resolution.expr, indexSystem))
            }
        }
        def grid_longlatascellid(lon: Column, lat: Column, resolution: Int): Column = grid_longlatascellid(lon, lat, lit(resolution))
        def grid_polyfill(geom: Column, resolution: Column): Column = {
            if (shouldUseDatabricksH3()) {
                getProductMethod("h3_polyfill")
                    .apply(geom, resolution)
                    .asInstanceOf[Column]
            } else {
                ColumnAdapter(Polyfill(geom.expr, resolution.expr, indexSystem, getGeometryAPI.name))
            }
        }
        def grid_polyfill(geom: Column, resolution: Int): Column = grid_polyfill(geom, lit(resolution))
        def grid_boundaryaswkb(indexID: Column): Column = {
            if (shouldUseDatabricksH3()) {
                getProductMethod("h3_boundaryaswkb")
                    .apply(indexID)
                    .asInstanceOf[Column]
            } else {
                ColumnAdapter(IndexGeometry(indexID.expr, lit("WKB").expr, indexSystem, getGeometryAPI.name))
            }
        }
        def grid_boundary(indexID: Column, format: Column): Column =
            ColumnAdapter(IndexGeometry(indexID.expr, format.expr, indexSystem, geometryAPI.name))
        def grid_boundary(indexID: Column, format: String): Column =
            ColumnAdapter(IndexGeometry(indexID.expr, lit(format).expr, indexSystem, geometryAPI.name))
        def grid_cellarea(cellId: Column): Column = ColumnAdapter(CellArea(cellId.expr, indexSystem, geometryAPI.name))
        def grid_cellkring(cellId: Column, k: Column): Column = ColumnAdapter(CellKRing(cellId.expr, k.expr, indexSystem, geometryAPI.name))
        def grid_cellkring(cellId: Column, k: Int): Column =
            ColumnAdapter(CellKRing(cellId.expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_cellkringexplode(cellId: Column, k: Int): Column =
            ColumnAdapter(CellKRingExplode(cellId.expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_cellkringexplode(cellId: Column, k: Column): Column =
            ColumnAdapter(CellKRingExplode(cellId.expr, k.expr, indexSystem, geometryAPI.name))
        def grid_cellkloop(cellId: Column, k: Column): Column = ColumnAdapter(CellKLoop(cellId.expr, k.expr, indexSystem, geometryAPI.name))
        def grid_cellkloop(cellId: Column, k: Int): Column =
            ColumnAdapter(CellKLoop(cellId.expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_cellkloopexplode(cellId: Column, k: Int): Column =
            ColumnAdapter(CellKLoopExplode(cellId.expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_cellkloopexplode(cellId: Column, k: Column): Column =
            ColumnAdapter(CellKLoopExplode(cellId.expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykring(geom: Column, resolution: Column, k: Column): Column =
            ColumnAdapter(GeometryKRing(geom.expr, resolution.expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykring(geom: Column, resolution: Column, k: Int): Column =
            ColumnAdapter(GeometryKRing(geom.expr, resolution.expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykring(geom: Column, resolution: Int, k: Column): Column =
            ColumnAdapter(GeometryKRing(geom.expr, lit(resolution).expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykring(geom: Column, resolution: Int, k: Int): Column =
            ColumnAdapter(GeometryKRing(geom.expr, lit(resolution).expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykring(geom: Column, resolution: String, k: Column): Column =
            ColumnAdapter(GeometryKRing(geom.expr, lit(resolution).expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykring(geom: Column, resolution: String, k: Int): Column =
            ColumnAdapter(GeometryKRing(geom.expr, lit(resolution).expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykringexplode(geom: Column, resolution: Column, k: Column): Column =
            ColumnAdapter(GeometryKRingExplode(geom.expr, resolution.expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykringexplode(geom: Column, resolution: Column, k: Int): Column =
            ColumnAdapter(GeometryKRingExplode(geom.expr, resolution.expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykringexplode(geom: Column, resolution: Int, k: Column): Column =
            ColumnAdapter(GeometryKRingExplode(geom.expr, lit(resolution).expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykringexplode(geom: Column, resolution: Int, k: Int): Column =
            ColumnAdapter(GeometryKRingExplode(geom.expr, lit(resolution).expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykringexplode(geom: Column, resolution: String, k: Column): Column =
            ColumnAdapter(GeometryKRingExplode(geom.expr, lit(resolution).expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykringexplode(geom: Column, resolution: String, k: Int): Column =
            ColumnAdapter(GeometryKRingExplode(geom.expr, lit(resolution).expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykloop(geom: Column, resolution: Column, k: Column): Column =
            ColumnAdapter(GeometryKLoop(geom.expr, resolution.expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykloop(geom: Column, resolution: Column, k: Int): Column =
            ColumnAdapter(GeometryKLoop(geom.expr, resolution.expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykloop(geom: Column, resolution: Int, k: Column): Column =
            ColumnAdapter(GeometryKLoop(geom.expr, lit(resolution).expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykloop(geom: Column, resolution: Int, k: Int): Column =
            ColumnAdapter(GeometryKLoop(geom.expr, lit(resolution).expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykloop(geom: Column, resolution: String, k: Column): Column =
            ColumnAdapter(GeometryKLoop(geom.expr, lit(resolution).expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykloop(geom: Column, resolution: String, k: Int): Column =
            ColumnAdapter(GeometryKLoop(geom.expr, lit(resolution).expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykloopexplode(geom: Column, resolution: Column, k: Column): Column =
            ColumnAdapter(GeometryKLoopExplode(geom.expr, resolution.expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykloopexplode(geom: Column, resolution: Column, k: Int): Column =
            ColumnAdapter(GeometryKLoopExplode(geom.expr, resolution.expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykloopexplode(geom: Column, resolution: Int, k: Column): Column =
            ColumnAdapter(GeometryKLoopExplode(geom.expr, lit(resolution).expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykloopexplode(geom: Column, resolution: Int, k: Int): Column =
            ColumnAdapter(GeometryKLoopExplode(geom.expr, lit(resolution).expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_geometrykloopexplode(geom: Column, resolution: String, k: Column): Column =
            ColumnAdapter(GeometryKLoopExplode(geom.expr, lit(resolution).expr, k.expr, indexSystem, geometryAPI.name))
        def grid_geometrykloopexplode(geom: Column, resolution: String, k: Int): Column =
            ColumnAdapter(GeometryKLoopExplode(geom.expr, lit(resolution).expr, lit(k).expr, indexSystem, geometryAPI.name))
        def grid_wrapaschip(cellID: Column, isCore: Boolean, getCellGeom: Boolean): Column =
            struct(
              lit(isCore).alias("is_core"),
              cellID.alias("index_id"),
              (if (getCellGeom) grid_boundaryaswkb(cellID) else lit(null)).alias("wkb")
            ).cast(
              ChipType(indexSystem.getCellIdDataType)
            ).alias("chip")

        // Not specific to Mosaic
        def try_sql(inCol: Column): Column = ColumnAdapter(TrySql(inCol.expr))

        // Legacy API
        @deprecated("Please use 'grid_boundaryaswkb' or 'grid_boundary(..., format_name)' expressions instead.")
        def index_geometry(indexID: Column): Column = grid_boundaryaswkb(indexID)
        @deprecated("Please use 'grid_tessellateexplode' expression instead.")
        def mosaic_explode(geom: Column, resolution: Column): Column = grid_tessellateexplode(geom, resolution)
        @deprecated("Please use 'grid_tessellateexplode' expression instead.")
        def mosaic_explode(geom: Column, resolution: Column, keepCoreGeometries: Boolean): Column =
            grid_tessellateexplode(geom, resolution, lit(keepCoreGeometries))
        @deprecated("Please use 'grid_tessellateexplode' expression instead.")
        def mosaic_explode(geom: Column, resolution: Column, keepCoreGeometries: Column): Column =
            grid_tessellateexplode(geom, resolution, keepCoreGeometries)
        @deprecated("Please use 'grid_tessellateexplode' expression instead.")
        def mosaic_explode(geom: Column, resolution: Int): Column = grid_tessellateexplode(geom, resolution)
        @deprecated("Please use 'grid_tessellateexplode' expression instead.")
        def mosaic_explode(geom: Column, resolution: Int, keepCoreGeometries: Boolean): Column =
            grid_tessellateexplode(geom, resolution, keepCoreGeometries)
        @deprecated("Please use 'grid_tessellateexplode' expression instead.")
        def mosaic_explode(geom: Column, resolution: Int, keepCoreGeometries: Column): Column =
            grid_tessellateexplode(geom, resolution, keepCoreGeometries)
        @deprecated("Please use 'grid_tessellate' expression instead.")
        def mosaicfill(geom: Column, resolution: Column): Column = grid_tessellate(geom, resolution)
        @deprecated("Please use 'grid_tessellate' expression instead.")
        def mosaicfill(geom: Column, resolution: Int): Column = grid_tessellate(geom, lit(resolution))
        @deprecated("Please use 'grid_tessellate' expression instead.")
        def mosaicfill(geom: Column, resolution: Column, keepCoreGeometries: Boolean): Column =
            grid_tessellate(geom, resolution, lit(keepCoreGeometries))
        @deprecated("Please use 'grid_tessellate' expression instead.")
        def mosaicfill(geom: Column, resolution: Int, keepCoreGeometries: Boolean): Column =
            grid_tessellate(geom, resolution, keepCoreGeometries)
        @deprecated("Please use 'grid_tessellate' expression instead.")
        def mosaicfill(geom: Column, resolution: Column, keepCoreGeometries: Column): Column =
            grid_tessellate(geom, resolution, keepCoreGeometries)
        @deprecated("Please use 'grid_tessellate' expression instead.")
        def mosaicfill(geom: Column, resolution: Int, keepCoreGeometries: Column): Column =
            grid_tessellate(geom, lit(resolution), keepCoreGeometries)
        @deprecated("Please use 'grid_pointascellid' expressions instead.")
        def point_index_geom(point: Column, resolution: Column): Column = grid_pointascellid(point, resolution)
        @deprecated("Please use 'grid_pointascellid' expressions instead.")
        def point_index_geom(point: Column, resolution: Int): Column = grid_pointascellid(point, resolution)
        @deprecated("Please use 'grid_longlatascellid' expressions instead.")
        def point_index_lonlat(lon: Column, lat: Column, resolution: Column): Column = grid_longlatascellid(lon, lat, resolution)
        @deprecated("Please use 'grid_longlatascellid' expressions instead.")
        def point_index_lonlat(lon: Column, lat: Column, resolution: Int): Column = grid_longlatascellid(lon, lat, resolution)
        @deprecated("Please use 'grid_polyfill' expressions instead.")
        def polyfill(geom: Column, resolution: Column): Column = grid_polyfill(geom, resolution)
        @deprecated("Please use 'grid_polyfill' expressions instead.")
        def polyfill(geom: Column, resolution: Int): Column = grid_polyfill(geom, resolution)
        @deprecated("Please use 'st_centroid' expressions instead.")
        def st_centroid2D(geom: Column): Column = {
            struct(
                ColumnAdapter(ST_X(ST_Centroid(geom.expr, expressionConfig), expressionConfig)),
                ColumnAdapter(ST_Y(ST_Centroid(geom.expr, expressionConfig), expressionConfig))
            )
        }

    }

}
// scalastyle:on object.name
// scalastyle:on line.size.limit

object MosaicContext extends Logging {

    private var instance: Option[MosaicContext] = None

    def build(indexSystem: IndexSystem, geometryAPI: GeometryAPI, rasterAPI: RasterAPI = GDAL): MosaicContext = {
        instance = Some(new MosaicContext(indexSystem, geometryAPI, rasterAPI))
        instance.get.setCellIdDataType(indexSystem.getCellIdDataType.typeName)
        context()
    }

    def read: MosaicDataFrameReader = new MosaicDataFrameReader(SparkSession.builder().getOrCreate())

    def geometryAPI: GeometryAPI = context().getGeometryAPI

    def rasterAPI: RasterAPI = context().getRasterAPI

    def indexSystem: IndexSystem = context().getIndexSystem

    def context(): MosaicContext =
        instance match {
            case Some(context) => context
            case None          => throw new Error("MosaicContext was not built.")
        }

    def reset(): Unit = instance = None

    // noinspection ScalaStyle
    def checkDBR(spark: SparkSession): Boolean = {
        val sparkVersion = spark.conf.get("spark.databricks.clusterUsageTags.sparkVersion", "")
        val isML = sparkVersion.contains("-ml-")
        val isPhoton = spark.conf.get("spark.databricks.photon.enabled", "false").toBoolean
        if (!isML && !isPhoton) {
            // Print out the warnings both to the log and to the console
            logWarning("DEPRECATION WARNING: Mosaic is not supported on the selected Databricks Runtime")
            logWarning("DEPRECATION WARNING: Mosaic will stop working on this cluster from version v0.4.0+.")
            logWarning("Please use a Databricks Photon-enabled Runtime (for performance benefits) or Runtime ML (for spatial AI benefits).")
            println("DEPRECATION WARNING: Mosaic is not supported on the selected Databricks Runtime")
            println("DEPRECATION WARNING: Mosaic will stop working on this cluster from version v0.4.0+.")
            println("Please use a Databricks Photon-enabled Runtime (for performance benefits) or Runtime ML (for spatial AI benefits).")
            false
        } else {
            true
        }
    }

}
