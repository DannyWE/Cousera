package quickcheck

import common._

import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {

  property("min1") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  property("min1") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  property("findMin1") = forAll { (a : Int, b : Int) =>
  {
    val result = insert(a, empty)
    val afterAdd = insert(b, result)

    (a, b) match {
      case (x, y) if x >= y => findMin(afterAdd) == y
      case (x, y) if x < y => findMin(afterAdd) == x
    }
  }
  }

  property("deleteMin1") = forAll { (a: Int) =>
    val result = insert(a, empty)

    val afterDelete = deleteMin(result)

    afterDelete == empty
  }

  property("ord1") = forAll { (list: List[Int]) =>
    val heap = toHeap(list)
    val orderedList = toOrderedList(heap)
    orderedList == list.sorted
  }

  def toHeap(list: List[Int]): H = list match {
    case Nil => empty
    case   x :: xs => insert(x, toHeap(xs))
  }

  def toOrderedList(heap: H): List[Int] = {
    if (isEmpty(heap)) Nil
    else findMin(heap) :: toOrderedList(deleteMin(heap))
  }


  lazy val genHeap: Gen[H] = ???

  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)

}
