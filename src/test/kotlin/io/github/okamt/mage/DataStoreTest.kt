package io.github.okamt.mage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import makeTestDatabase
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

private suspend inline fun <reified KEY : Comparable<KEY>, reified DATA> StringSpec.makeSingleDataStoreTest(keyArb: Arb<KEY>) =
    checkAll<KEY, DATA> { key, data ->
        with(SingleDataStore.make(key, data)) {
            hasData(key) shouldBe true
            hasData(keyArb.next()) shouldBe true
            withData(keyArb.next()) {} shouldBe true
            writeData(keyArb.next()) shouldBe Unit
            makeData {} shouldBeSameInstanceAs key
            newData(keyArb.next()) {} shouldBeSameInstanceAs data
            getData(keyArb.next()) shouldBeSameInstanceAs data
            setData(keyArb.next(), data) shouldBeSameInstanceAs data
            removeData(keyArb.next()) shouldBeSameInstanceAs data
        }
    }

private suspend inline fun <reified KEY : Comparable<KEY>, reified DATA> StringSpec.makeVolatileDataStoreTest(dataStore: VolatileDataStore<KEY, DATA>) =
    checkAll<KEY, DATA> { key, data ->
        with(dataStore) {
            fun dataShouldBeThere(theKey: KEY, theData: DATA) {
                hasData(theKey) shouldBe true
                getData(theKey) shouldBeSameInstanceAs theData
                var executed = false
                withData(theKey) { executed = true } shouldBe true
                executed shouldBe true
            }

            fun dataShouldNotBeThere(theKey: KEY) {
                hasData(theKey) shouldBe false
                getData(theKey) shouldBe null
                var executed = false
                withData(theKey) { executed = true } shouldBe false
                executed shouldBe false
                removeData(theKey) shouldBe null
            }

            dataShouldNotBeThere(key)
            setData(key, data) shouldBe null
            dataShouldBeThere(key, data)
            removeData(key) shouldBeSameInstanceAs data
            dataShouldNotBeThere(key)
            writeData(key)
            val newData = getData(key).shouldNotBeNull()
            dataShouldBeThere(key, newData)
            removeData(key)
        }
    }

class DataStoreTest : StringSpec({
    "SingleDataStore" {
        makeSingleDataStoreTest<String, String>(Arb.string())
        makeSingleDataStoreTest<Int, Int>(Arb.int())
        makeSingleDataStoreTest<Long, MyData>(Arb.long())
    }

    "VolatileDataStore" {
        makeVolatileDataStoreTest<String, String>(VolatileDataStore(dataMaker = { "" }))
        makeVolatileDataStoreTest<Int, Int>(VolatileDataStore(dataMaker = { 0 }))
        makeVolatileDataStoreTest<Long, MyData>(VolatileDataStore(dataMaker = { MyData.default }))

        shouldThrow<IllegalStateException> {
            val dataStore = VolatileDataStore<Int, Int>(null) { 1 }
            dataStore.makeData {}
        }
    }

    "PersistentData" {
        val database = makeTestDatabase()
        newSuspendedTransaction(db = database) {
            val table = object : IntIdTable("persistentDataTest") {
                val myInt = integer("myInt").default(0)
            }

            class TestData(id: EntityID<Int>) : PersistentData<Int>(id) {
                var myInt by table.myInt
            }

            SchemaUtils.listTables().shouldBeEmpty()
            val store = PersistentData.Store(table, ::TestData)
            SchemaUtils.listTables() shouldHaveSize 1

            with(store) {
                checkAll<Int> { key ->
                    fun dataShouldBeThere(theKey: Int, theData: PersistentData<Int>) {
                        hasData(theKey) shouldBe true
                        var equal = false
                        withData(theKey) { equal = this == theData } shouldBe true
                        equal shouldBe true
                    }

                    fun dataShouldNotBeThere(theKey: Int) {
                        hasData(theKey) shouldBe false
                        var executed = false
                        withData(theKey) { executed = true } shouldBe false
                        executed shouldBe false
                    }

                    dataShouldNotBeThere(key)
                    writeData(key)
                    lateinit var data: PersistentData<Int>
                    withData(key) { data = this }
                    dataShouldBeThere(key, data)
                    data.delete()
                    dataShouldNotBeThere(key)
                }
            }
        }
    }
}) {
    class MyData(
        var myInt: Int,
        var myString: String,
        var myDouble: Double,
    ) {
        companion object {
            val default = MyData(0, "", 0.0)
        }
    }
}