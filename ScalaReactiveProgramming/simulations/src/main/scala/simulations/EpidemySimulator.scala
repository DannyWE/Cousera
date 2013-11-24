package simulations

import math.random

class EpidemySimulator extends Simulator {

  def randomBelow(i: Int) = (random * i).toInt

  protected[simulations] object SimConfig {
    val population: Int = 300
    val roomRows: Int = 8
    val roomColumns: Int = 8

    // to complete: additional parameters of simulation

    val incubationTime = 6
    val dieTime = 14
    val immuneTime = 16
    val healTime = 18
    val prevalenceRate = 0.01
    val transRate = 0.4
    val dieRate = 0.25
  }

  import SimConfig._

  val persons: List[Person] = for {
      i <- (0 until population).toList
  } yield {
    val p = new Person(i)
    if ( i < population * prevalenceRate)
      p.setInfected()
    p.mode()
    p
  }
  // to complete: construct list of persons

  class Person (val id: Int) {
    var infected = false
    var sick = false
    var immune = false
    var dead = false

    // demonstrates random number generation
    var row: Int = randomBelow(roomRows)
    var col: Int = randomBelow(roomColumns)

    //
    // to complete with simulation logic
    //

    def setInfected() {
      infected = true
      afterDelay (incubationTime) (setSick)
      afterDelay (dieTime) (setDead)
      afterDelay (immuneTime) (setImmune)
      afterDelay (healTime) (setHealthy)
    }

    def setSick() {
      sick = true
    }

    def setDead() {
      if (random < dieRate)
        dead = true
    }

    def setImmune() {
      if (dead)
        return
      sick = false
      immune = true
    }

    def setHealthy() {
      if (dead)
        return
      immune = false
      infected = false
    }

    def mode() {
      val moveDelay = randomBelow(5) + 1
      afterDelay (moveDelay) (move)
    }

    def move() {
      if (dead)
        return
      //// Move to neighbor room
      val neighbors = List(((row-1+roomRows) % roomRows, col),
        ((row+1) % roomRows, col),
        (row, (col-1+roomColumns) % roomColumns),
        (row, (col+1) % roomColumns))
      def isHealthy(room: (Int, Int)): Boolean = room match {
        case (r, c) => (persons.find{p => p.row==r && p.col==c && (p.sick || p.dead)}).isEmpty
      }
      val candidates = neighbors filter isHealthy
      if (!candidates.isEmpty) {
        val candidate: (Int,Int) = candidates(randomBelow(candidates.length))
        candidate match {
          case (a,b) => row = a; col = b
        }
      }
      //// Get infected if anyone in the same room is infected and I'm not already immune/infected.
      if (!immune && !infected)
        if (random < transRate)
          if (!(persons.find{p => p.row==row && p.col==col && p.infected}).isEmpty)
            setInfected()
      //// Set a timer for next move
      mode()
    }
  }
}
