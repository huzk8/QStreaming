package com.qiniu.stream.spark.core

import com.qiniu.stream.core.parser.SqlParser
import com.qiniu.stream.core.job.{Job, SqlStatement}
import com.qiniu.stream.spark.config._
import com.qiniu.stream.spark.util.DslUtil
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.misc.Interval


case class SparkJob(jobContent: CharStream, params: Map[String, String] = Map()) extends Job with Logging {


  def findSinkTable(tableName: String): Option[SinkTable] = statements
    .filter(_.isInstanceOf[SinkTable])
    .map(_.asInstanceOf[SinkTable])
    .find(_.name == tableName)


  /**
   * {@inheritDoc }
   *
   * <p>The default implementation does nothing.</p>
   */
  override def exitSqlStatement(ctx: SqlParser.SqlStatementContext): Unit = {
    printStatement(ctx)
    val statement = {
      val interval = new Interval(ctx.start.getStartIndex, ctx.stop.getStopIndex)
      ctx.start.getInputStream.getText(interval)
    }
    addStatement(SqlStatement(statement))
  }

  /**
   *
   **/
  override def exitCreateSourceTableStatement(ctx: SqlParser.CreateSourceTableStatementContext): Unit = {
    printStatement(ctx)
    addStatement(TableFactory.createSourceTable(ctx))
  }

  override def exitCreateSinkTableStatement(ctx: SqlParser.CreateSinkTableStatementContext): Unit = {
    printStatement(ctx)
    addStatement(TableFactory.createSinkTable(ctx))
  }


  override def exitCreateViewStatement(ctx: SqlParser.CreateViewStatementContext): Unit = {
    printStatement(ctx)
    import scala.collection.convert.wrapAsScala._
    val options = ctx.property().map(DslUtil.parseProperty).toMap
    val viewType = {
      if (ctx.K_GLOBAL() != null && ctx.K_TEMPORARY() != null) {
        ViewType.globalView
      } else if (ctx.K_TEMPORARY() != null) {
        ViewType.tempView
      } else if (ctx.K_PERSISTED() != null) {
        ViewType.persistedView
      } else {
        //为了兼容0.1.0的create view用法，0.1.0都是tempView
        ViewType.tempView
      }
    }

    val statement = CreateViewStatement(DslUtil.parseSql(ctx.selectStatement()), ctx.tableName().getText, options, viewType)
    addStatement(statement)
  }


  override def exitInsertStatement(ctx: SqlParser.InsertStatementContext): Unit = {
    printStatement(ctx)
    val sql = DslUtil.parseSql(ctx.selectStatement())
    val tableName = ctx.tableName().getText
    val sinkTableOption = findSinkTable(tableName)
    sinkTableOption match {
      case Some(sinkTable) => {
        val statement = InsertStatement(sql, sinkTable)
        addStatement(statement)
      }
      case None =>
        addStatement(SqlStatement(s"insert into ${tableName} ${sql}"))
    }
  }

  override def exitCreateFunctionStatement(ctx: SqlParser.CreateFunctionStatementContext): Unit = {
    printStatement(ctx)
    import scala.collection.JavaConverters._
    val funcBody = {
      val interval = new Interval(ctx.funcBody.start.getStartIndex, ctx.funcBody.stop.getStopIndex)
      ctx.funcBody.start.getInputStream.getText(interval)
    }
    val dataType = if (ctx.functionDataType() != null) {
      val fields = ctx.functionDataType().structField().asScala
      val fieldTypes = fields.map(field => {
        val fieldName = DslUtil.cleanQuote(field.STRING().getText)
        val fieldType = field.fieldType().getText
        SqlField(fieldName, SqlDataType(fieldType))
      })
      Some(SqlStructType(fieldTypes.toArray))
    } else {
      None
    }
    val funcParams = Option(ctx.funcParam()).map(_.asScala.map(_.getText).mkString(","))
    addStatement(CreateFunctionStatement(dataType, ctx.funcName.getText, funcParams, funcBody))
  }

  parseJob(jobContent, params)

}