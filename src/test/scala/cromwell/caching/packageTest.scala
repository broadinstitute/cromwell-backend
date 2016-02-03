package cromwell.caching

import better.files._
import org.scalatest.{Matchers, WordSpecLike}
import wdl4s.types.{WdlArrayType, WdlFileType, WdlMapType, WdlStringType}
import wdl4s.values.{WdlArray, WdlMap, WdlSingleFile, WdlString}

class packageTest extends WordSpecLike with Matchers {
  "A hash computation" should {
    "be computed over a WdlString" in {
      val wdlValue = WdlString("Pepe")
      val expectedHash = "e885d567f57b0f87333c25f7f3a1e381"

      val actualHash = caching.computeWdlValueHash(wdlValue)

      assert(expectedHash.equals(actualHash))
    }

    "be computed over a WdlArray(WdlString)" in {
      val wdlValue = WdlString("Pepe")
      val wdlValue2 = WdlString("Pepe2")
      val wdlValue3 = WdlString("Pepe3")
      val wdlArray = WdlArray.apply(WdlArrayType(WdlStringType), Seq(wdlValue, wdlValue2, wdlValue3))
      val expectedHash = "517ff4e80f3efbbafc4f4ec3854583bd"

      val actualHash = caching.computeWdlValueHash(wdlArray)

      assert(expectedHash.equals(actualHash))
    }

    "be computed over a WdlFile" in {
      val file = "pepe.txt".toFile < "hello"
      val wdlValue = WdlSingleFile(file.name)
      val expectedHash = "5d41402abc4b2a76b9719d911017c592"

      val actualHash = caching.computeWdlValueHash(wdlValue)

      file.delete()
      assert(expectedHash.equals(actualHash))
    }

    "be computed over a WdlArray(WdlSingleFile)" in {
      val file = "Pepe".toFile < "hello"
      val file2 = "Pepe2".toFile < "hello1"
      val file3 = "Pepe3".toFile < "hello2"
      val wdlArray = WdlArray.apply(WdlArrayType(WdlFileType), Seq(WdlSingleFile(file.name), WdlSingleFile(file2.name), WdlSingleFile(file3.name)))
      val expectedHash = "30a2d1b814eedf49192482611b04fb67"

      val actualHash = caching.computeWdlValueHash(wdlArray)

      file.delete()
      file2.delete()
      file3.delete()
      assert(expectedHash.equals(actualHash))
    }

    "be computed over a WdlMap(WdlString -> WdlSingleFile)" in {
      val wdlString = WdlString("Pepe")
      val wdlString2 = WdlString("Pepe")
      val file = "Pepe".toFile < "hello"
      val wdlFile = WdlSingleFile(file.name)
      val file2 = "Pepe2".toFile < "hello1"
      val wdlFile2 = WdlSingleFile(file.name)
      val wdlMap = WdlMap(WdlMapType(WdlStringType, WdlFileType), Map(wdlString2 -> wdlFile2, wdlString -> wdlFile))
      val expectedHash = "0eb8eecdb31d870ecffb8e2657693422"

      val actualHash = caching.computeWdlValueHash(wdlMap)

      file.delete()
      file2.delete()
      assert(expectedHash.equals(actualHash))
    }
  }
}
