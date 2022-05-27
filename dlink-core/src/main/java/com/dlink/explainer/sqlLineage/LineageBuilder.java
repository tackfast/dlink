package com.dlink.explainer.sqlLineage;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.stat.TableStat;
import com.dlink.assertion.Asserts;
import com.dlink.explainer.lineage.LineageRelation;
import com.dlink.explainer.lineage.LineageResult;
import com.dlink.explainer.lineage.LineageTable;

import java.util.*;

public class LineageBuilder {
    public static LineageResult getSqlLineageByOne(String statement ,String type) {
        List<LineageTable> tables = new ArrayList<>();
        List<LineageRelation> relations = new ArrayList<>();
        try {
            List<SQLStatement> sqlStatements = SQLUtils.parseStatements(statement.toLowerCase(), type);
            // 只考虑一条语句
            SQLStatement sqlStatement = sqlStatements.get(0);
            List<List<TableStat.Column>> srcLists = new ArrayList<>();
            List<TableStat.Column> tgtList = new ArrayList<>();
            //只考虑insert语句
            if (sqlStatement instanceof SQLInsertStatement) {
                String targetTable = ((SQLInsertStatement) sqlStatement).getTableName().toString();
                List<SQLExpr> columns = ((SQLInsertStatement) sqlStatement).getColumns();
                //处理target表中字段
                for (SQLExpr column : columns) {
                    if (column instanceof SQLPropertyExpr) {
                        tgtList.add(new TableStat.Column(targetTable, ((SQLPropertyExpr) column).getName().replace("`", "").replace("\"", "")));
                    } else if (column instanceof SQLIdentifierExpr) {
                        tgtList.add(new TableStat.Column(targetTable, ((SQLIdentifierExpr) column).getName().replace("`", "").replace("\"", "")));
                    }
                }
                //处理select  生成srcLists
                LineageColumn root = new LineageColumn();
                TreeNode<LineageColumn> rootNode = new TreeNode<>(root);
                LineageUtils.columnLineageAnalyzer(((SQLInsertStatement) sqlStatement).getQuery().toString(), type, rootNode);
                for (TreeNode<LineageColumn> e : rootNode.getChildren()) {
                    Set<LineageColumn> leafNodes = e.getAllLeafData();
                    List<TableStat.Column> srcList = new ArrayList<>();
                    for (LineageColumn column : leafNodes) {
                        String tableName = Asserts.isNotNullString(column.getSourceTableName()) ? (Asserts.isNotNullString(column.getSourceDbName()) ? column.getSourceDbName()
                                + "." + column.getSourceTableName() : column.getSourceTableName()) : "";
                        srcList.add(new TableStat.Column(tableName, column.getTargetColumnName()));
                    }
                    srcLists.add(srcList);
                }
                // 构建 List<LineageTable>
                Map<String, String> tableMap = new HashMap<>();
                List<TableStat.Column> allColumnList = new ArrayList<>();
                int tid = 100;
                for (TableStat.Column column : tgtList) {
                    if (Asserts.isNotNullString(column.getTable()) && !tableMap.containsKey(column.getTable())) {
                        tableMap.put(column.getTable(), String.valueOf(tid++));
                    }
                }
                for (List<TableStat.Column> columnList : srcLists) {
                    allColumnList.addAll(columnList);
                    for (TableStat.Column column : columnList) {
                        if (Asserts.isNotNullString(column.getTable()) && !tableMap.containsKey(column.getTable())) {
                            tableMap.put(column.getTable(), String.valueOf(tid++));
                        }
                    }
                }
                allColumnList.addAll(tgtList);
                for (String tableName : tableMap.keySet()) {
                    LineageTable table = new LineageTable();
                    table.setId(tableMap.get(tableName));
                    table.setName(tableName);
                    List<com.dlink.explainer.lineage.LineageColumn> tableColumns = new ArrayList<>();
                    Set<String> tableSet = new HashSet<>();
                    for (TableStat.Column column : allColumnList) {
                        if (tableName.equals(column.getTable()) && !tableSet.contains(column.getName())) {
                            tableColumns.add(new com.dlink.explainer.lineage.LineageColumn(column.getName(), column.getName()));
                            tableSet.add(column.getName());
                        }
                    }
                    table.setColumns(tableColumns);
                    tables.add(table);
                }
                // 构建 LineageRelation
                int tSize = tgtList.size();
                int sSize = srcLists.size();
                if (tSize != sSize && tSize * 2 != sSize) {
                    System.out.println("出现字段位数不相等错误");
                    return null;
                }
                for (int i = 0; i < tSize; i++) {
                    for (TableStat.Column column : srcLists.get(i)) {
                        if (Asserts.isNotNullString(column.getTable())) {
                            relations.add(LineageRelation.build(i + "",
                                    tableMap.get(column.getTable()),
                                    tableMap.get(tgtList.get(i).getTable()),
                                    column.getName(),
                                    tgtList.get(i).getName()));
                        }
                    }
                    if (tSize * 2 == sSize) {
                        for (TableStat.Column column : srcLists.get(i + tSize)) {
                            if (Asserts.isNotNullString(column.getTable())) {
                                relations.add(LineageRelation.build((i + tSize) + "",
                                        tableMap.get(column.getTable()),
                                        tableMap.get(tgtList.get(i).getTable()),
                                        column.getName(),
                                        tgtList.get(i).getName()));
                            }
                        }
                    }
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return LineageResult.build(tables, relations);
    }

    public static LineageResult getSqlLineage(String statement,String type) {
        List<LineageTable> tables = new ArrayList<>();
        List<LineageRelation> relations = new ArrayList<>();
        Map<Integer, List<List<TableStat.Column>>> srcMap = new HashMap<>();
        Map<Integer, List<TableStat.Column>> tgtMap = new HashMap<>();
        Map<String, String> tableMap = new HashMap<>();
        List<TableStat.Column> allColumnList = new ArrayList<>();

        try {
            List<SQLStatement> sqlStatements = SQLUtils.parseStatements(statement.toLowerCase(), type);
            for (int n = 0; n < sqlStatements.size(); n++) {
                SQLStatement sqlStatement = sqlStatements.get(n);
                List<List<TableStat.Column>> srcLists = new ArrayList<>();
                List<TableStat.Column> tgtList = new ArrayList<>();
                //只考虑insert语句
                if (sqlStatement instanceof SQLInsertStatement) {
                    String targetTable = ((SQLInsertStatement) sqlStatement).getTableName().toString();
                    List<SQLExpr> columns = ((SQLInsertStatement) sqlStatement).getColumns();
                    //处理target表中字段
                    for (SQLExpr column : columns) {
                        if (column instanceof SQLPropertyExpr) {
                            tgtList.add(new TableStat.Column(targetTable, ((SQLPropertyExpr) column).getName().replace("`", "").replace("\"", "")));
                        } else if (column instanceof SQLIdentifierExpr) {
                            tgtList.add(new TableStat.Column(targetTable, ((SQLIdentifierExpr) column).getName().replace("`", "").replace("\"", "")));
                        }
                    }
                    //处理select  生成srcLists
                    LineageColumn root = new LineageColumn();
                    TreeNode<LineageColumn> rootNode = new TreeNode<>(root);
                    LineageUtils.columnLineageAnalyzer(((SQLInsertStatement) sqlStatement).getQuery().toString(), type, rootNode);
                    for (TreeNode<LineageColumn> e : rootNode.getChildren()) {
                        Set<LineageColumn> leafNodes = e.getAllLeafData();
                        List<TableStat.Column> srcList = new ArrayList<>();
                        for (LineageColumn column : leafNodes) {
                            String tableName = Asserts.isNotNullString(column.getSourceTableName()) ? (Asserts.isNotNullString(column.getSourceDbName()) ? column.getSourceDbName()
                                    + "." + column.getSourceTableName() : column.getSourceTableName()) : "";
                            srcList.add(new TableStat.Column(tableName, column.getTargetColumnName()));
                        }
                        srcLists.add(srcList);
                    }
                    srcMap.put(n, srcLists);
                    tgtMap.put(n, tgtList);
                } else {
                    return null;
                }
            }
            // 构建 List<LineageTable>
            int tid = 100;
            for (Integer i : tgtMap.keySet()) {
                allColumnList.addAll(tgtMap.get(i));
                for (TableStat.Column column : tgtMap.get(i)) {
                    if (Asserts.isNotNullString(column.getTable()) && !tableMap.containsKey(column.getTable())) {
                        tableMap.put(column.getTable(), String.valueOf(tid++));
                    }
                }
            }
            for (Integer i : srcMap.keySet()) {
                for (List<TableStat.Column> columnList : srcMap.get(i)) {
                    allColumnList.addAll(columnList);
                    for (TableStat.Column column : columnList) {
                        if (Asserts.isNotNullString(column.getTable()) && !tableMap.containsKey(column.getTable())) {
                            tableMap.put(column.getTable(), String.valueOf(tid++));
                        }
                    }
                }
            }
            for (String tableName : tableMap.keySet()) {
                LineageTable table = new LineageTable();
                table.setId(tableMap.get(tableName));
                table.setName(tableName);
                List<com.dlink.explainer.lineage.LineageColumn> tableColumns = new ArrayList<>();
                Set<String> tableSet = new HashSet<>();
                for (TableStat.Column column : allColumnList) {
                    if (tableName.equals(column.getTable()) && !tableSet.contains(column.getName())) {
                        tableColumns.add(new com.dlink.explainer.lineage.LineageColumn(column.getName(), column.getName()));
                        tableSet.add(column.getName());
                    }
                }
                table.setColumns(tableColumns);
                tables.add(table);
            }
            // 构建 LineageRelation
            for (Integer n : srcMap.keySet()) {
                List<List<TableStat.Column>> srcLists = srcMap.get(n);
                List<TableStat.Column> tgtList = tgtMap.get(n);
                int tSize = tgtList.size();
                int sSize = srcLists.size();
                if (tSize != sSize && tSize * 2 != sSize) {
                    System.out.println("出现字段位数不相等错误");
                    return null;
                }
                for (int i = 0; i < tSize; i++) {
                    for (TableStat.Column column : srcLists.get(i)) {
                        if (Asserts.isNotNullString(column.getTable())) {
                            relations.add(LineageRelation.build(n + "_" + i,
                                    tableMap.get(column.getTable()),
                                    tableMap.get(tgtList.get(i).getTable()),
                                    column.getName(),
                                    tgtList.get(i).getName()));
                        }
                    }
                    if (tSize * 2 == sSize) {
                        for (TableStat.Column column : srcLists.get(i + tSize)) {
                            if (Asserts.isNotNullString(column.getTable())) {
                                relations.add(LineageRelation.build(n + "_" + (i + tSize),
                                        tableMap.get(column.getTable()),
                                        tableMap.get(tgtList.get(i).getTable()),
                                        column.getName(),
                                        tgtList.get(i).getName()));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return LineageResult.build(tables, relations);
    }

}
