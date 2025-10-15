package com.uav.analytics.utils

import android.util.Log

class DataTableLogger {
    companion object {
        private const val COLUMN_WIDTH_TIME = 18
        private const val COLUMN_WIDTH_OLD = 10
        private const val COLUMN_WIDTH_NEW = 10
        private const val COLUMN_WIDTH_TOTAL = 12
        private const val COLUMN_WIDTH_UNIQUE = 10
        private const val COLUMN_WIDTH_RUNNING = 15
        private const val COLUMN_WIDTH_NOTES = 22

        fun printTableHeader() {
            val header = StringBuilder()
            header.append("FACE DETECTION DATA TABLE - VADODARA BUS STATION\n")
            header.append("=".repeat(100)).append("\n")
            header.append("| ${"Time Period (IST)".padEnd(COLUMN_WIDTH_TIME)} | ")
            header.append("${"Old People".padEnd(COLUMN_WIDTH_OLD)} | ")
            header.append("${"New People".padEnd(COLUMN_WIDTH_NEW)} | ")
            header.append("${"Total People".padEnd(COLUMN_WIDTH_TOTAL)} | ")
            header.append("${"Unique New".padEnd(COLUMN_WIDTH_UNIQUE)} | ")
            header.append("${"Running Total".padEnd(COLUMN_WIDTH_RUNNING)} | ")
            header.append("${"Notes on Active Faces".padEnd(COLUMN_WIDTH_NOTES)} |\n")
            header.append("|${"-".repeat(COLUMN_WIDTH_TIME + 2)}|${"-".repeat(COLUMN_WIDTH_OLD + 2)}|${"-".repeat(COLUMN_WIDTH_NEW + 2)}|${"-".repeat(COLUMN_WIDTH_TOTAL + 2)}|${"-".repeat(COLUMN_WIDTH_UNIQUE + 2)}|${"-".repeat(COLUMN_WIDTH_RUNNING + 2)}|${"-".repeat(COLUMN_WIDTH_NOTES + 2)}|\n")

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
            row.append("${notes.padEnd(COLUMN_WIDTH_NOTES)} |")

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
            footer.append("=".repeat(100))

            Log.d("DataTable", footer.toString())
        }

        fun printNewPeriodHeader() {
            val newPeriod = StringBuilder()
            newPeriod.append("\n")
            newPeriod.append("NEW TRACKING PERIOD STARTED\n")
            newPeriod.append("=".repeat(100)).append("\n")

            Log.d("DataTable", newPeriod.toString())
        }
    }
}