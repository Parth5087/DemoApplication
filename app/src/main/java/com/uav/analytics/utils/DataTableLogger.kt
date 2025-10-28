package com.uav.analytics.utils

import android.util.Log

class DataTableLogger {
    companion object {
        private const val COLUMN_WIDTH_TIME = 15
        private const val COLUMN_WIDTH_OLD = 6
        private const val COLUMN_WIDTH_NEW = 6
        private const val COLUMN_WIDTH_TOTAL = 6
        private const val COLUMN_WIDTH_UNIQUE = 6
        private const val COLUMN_WIDTH_RUNNING = 8
        private const val COLUMN_WIDTH_NOTES = 22

        fun printTableHeader() {
            val header = StringBuilder()
            header.append("FACE DETECTION DATA TABLE - VADODARA BUS STATION\n")
            header.append("=".repeat(90)).append("\n")
            header.append("| ${"Time".padEnd(COLUMN_WIDTH_TIME)} | ")
            header.append("${"Old".padEnd(COLUMN_WIDTH_OLD)} | ")
            header.append("${"New".padEnd(COLUMN_WIDTH_NEW)} | ")
            header.append("${"Total".padEnd(COLUMN_WIDTH_TOTAL)} | ")
            header.append("${"Unique".padEnd(COLUMN_WIDTH_UNIQUE)} | ")
            header.append("${"Running".padEnd(COLUMN_WIDTH_RUNNING)} | ")
            header.append("${"Notes".padEnd(COLUMN_WIDTH_NOTES)} \n")
            header.append("|${"-".repeat(COLUMN_WIDTH_TIME + 2)}|${"-".repeat(COLUMN_WIDTH_OLD + 2)}|${"-".repeat(COLUMN_WIDTH_NEW + 2)}|${"-".repeat(COLUMN_WIDTH_TOTAL + 2)}|${"-".repeat(COLUMN_WIDTH_UNIQUE + 2)}|${"-".repeat(COLUMN_WIDTH_RUNNING + 2)}|${"-".repeat(COLUMN_WIDTH_NOTES + 2)}\n")

            Log.d("DataTable", header.toString())
        }

        fun printTableRow(
            timePeriod: String,
            oldPeople: Int,
            newPeople: Int,
            totalPeopleSeen: Int,
            uniqueNewArrivals: Int,
            runningTotalNewArrivals: Int,
            notes: String
        ) {
            val row = StringBuilder()
            row.append("| ${timePeriod.padEnd(COLUMN_WIDTH_TIME)} | ")
            row.append("${oldPeople.toString().padEnd(COLUMN_WIDTH_OLD)} | ")
            row.append("${newPeople.toString().padEnd(COLUMN_WIDTH_NEW)} | ")
            row.append("${totalPeopleSeen.toString().padEnd(COLUMN_WIDTH_TOTAL)} | ")
            row.append("${uniqueNewArrivals.toString().padEnd(COLUMN_WIDTH_UNIQUE)} | ")
            row.append("${runningTotalNewArrivals.toString().padEnd(COLUMN_WIDTH_RUNNING)} | ")
            row.append("${notes.padEnd(COLUMN_WIDTH_NOTES)} ")

            Log.d("DataTable", row.toString())
        }

        fun printTableFooter(totalPeopleSeen: Int, totalNewArrivals: Int) {
            val footer = StringBuilder()
            footer.append("|${"-".repeat(COLUMN_WIDTH_TIME + 2)}|${"-".repeat(COLUMN_WIDTH_OLD + 2)}|${"-".repeat(COLUMN_WIDTH_NEW + 2)}|${"-".repeat(COLUMN_WIDTH_TOTAL + 2)}|${"-".repeat(COLUMN_WIDTH_UNIQUE + 2)}|${"-".repeat(COLUMN_WIDTH_RUNNING + 2)}|${"-".repeat(COLUMN_WIDTH_NOTES + 2)}|\n")
            footer.append("| ${"Total (10 min)".padEnd(COLUMN_WIDTH_TIME)} | ")
            footer.append("${"-".padEnd(COLUMN_WIDTH_OLD)} | ")
            footer.append("${"-".padEnd(COLUMN_WIDTH_NEW)} | ")
            footer.append("${totalPeopleSeen.toString().padEnd(COLUMN_WIDTH_TOTAL)} | ")
            footer.append("${"-".padEnd(COLUMN_WIDTH_UNIQUE)} | ")
            footer.append("${totalNewArrivals.toString().padEnd(COLUMN_WIDTH_RUNNING)} | ")
            footer.append("${"-".padEnd(COLUMN_WIDTH_NOTES)} |\n")
            footer.append("=".repeat(90))

            Log.d("DataTable", footer.toString())
        }
    }
}