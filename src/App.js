import React, { useState } from 'react';
import { AppBar, Box, Button, createTheme, CssBaseline, FormControl, Grid, makeStyles, Modal, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Toolbar, Typography } from '@material-ui/core';
import { ThemeProvider } from '@material-ui/styles';
import { useForm } from 'react-hook-form';

import firebase from 'firebase/compat/app';
import 'firebase/compat/firestore';
import 'firebase/compat/auth';

import { useAuthState } from 'react-firebase-hooks/auth';
import { useCollectionData } from 'react-firebase-hooks/firestore';
import SignIn from './components/SignIn';
import AccountSignedIn from './components/AccountSignedIn';

const darkTheme = createTheme({
  palette: {
    type: 'dark',
  }
});

firebase.initializeApp({
  apiKey: "AIzaSyAtQtSFrmXIylCOktouGw2YsnKwGgv6U3k",
  authDomain: "plant0.firebaseapp.com",
  projectId: "plant0",
  storageBucket: "plant0.appspot.com",
  messagingSenderId: "438322523913",
  appId: "1:438322523913:web:8258be948ab81d86616dd8"
});

const useStyles = makeStyles(() => ({
  mainLayoutGrid: {
    minHeight: "95vh",
    width: "100vw",
    maxWidth: "100%"
  },
  verticalMarginGrid: {
    minHeight: "10%",
    maxHeight: "10%"
  },
  contentGrid: {
    minHeight: "80%"
  }, 
  addPlantModal: {
    position: 'absolute',
    top: '50%',
    left: '50%',
    transform: 'translate(-50%, -50%)',
    width: 400,
    bgcolor: 'background.paper',
    border: '2px solid #000',
    boxShadow: 24,
    p: 4
  }
}));

const auth = firebase.auth();
const firestore = firebase.firestore();

function App() {
  const classes = useStyles();
  const [user] = useAuthState(auth);
  const plantsRef = firestore.collection("plants");
  const query = plantsRef.where("uid", "==", user ? auth.currentUser.uid : '')
  const [plants] = useCollectionData(query, { idField: 'id' });
  const [openAddPlant, setOpenAddPlant] = useState(false);
  const { register, handleSubmit } = useForm();
  var content;

  const handleWater = (plant) => {
    console.log(plant)
    var newDateForWatering = new Date(new Date().getTime() + (plant.wateringGap * (1000 * 3600 * 24)));
    plantsRef.doc(plant.id).update({ lastWatering: new Date(), nextWatering: newDateForWatering });
  }

  const onSubmit = (form) => {
    alert(JSON.stringify(form));
  }

  const modalStyle = {
    position: 'absolute',
    top: '50%',
    left: '50%',
    transform: 'translate(-50%, -50%)',
    width: 400,
    bgcolor: 'background.paper',
    border: '2px solid #000',
    boxShadow: 24,
    p: 4,
  };

  if(user) {
    content = <TableContainer component={Paper}>
    <Table sx={{ minWidth: 650}} aria-label="plant table">
      <TableHead>
        <TableRow>
          <TableCell>Plant Name</TableCell>
          <TableCell>Last Watered Date</TableCell>
          <TableCell>Next Watering Date</TableCell>
          <TableCell></TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {
          plants?.map(plant => (
            <TableRow key={plant.id} sx={{ '&:last-child td, &:last-child th': { border: 0 } }}>
              <TableCell scope="plant">{plant.plantName}</TableCell>
              <TableCell>{plant.lastWatering.toDate().toString()}</TableCell>
              <TableCell>{plant.nextWatering.toDate().toString()}</TableCell>
              <TableCell>
                <Button variant="contained" onClick={() => handleWater(plant)}>
                  Water This Plant
                </Button>
              </TableCell>
            </TableRow>
          ))
        }
        <TableRow style={{ padding: "5px", alignItems: "center"}}>
          <TableCell />
          <TableCell />
          <TableCell />
          <TableCell>
            <Button variant="contained" onClick={() => setOpenAddPlant(!openAddPlant)}>Add New Plant</Button>
            <Modal
              open={openAddPlant}
              onClose={() => setOpenAddPlant(false)}
            >
              <Box sx={modalStyle}>
              <form onSubmit={handleSubmit(onSubmit)}>
                <FormControl>
                  <TextField
                    name="plantName"
                    label="Plant Name"
                    variant="outlined"
                    inputRef={register()}
                  />
                  <TextField
                    name="daysToWater"
                    label="Days Between Watering"
                    variant="outlined"
                    type="number"
                    inputRef={register()}
                  />
                  <Button variant="contained">Add Plant</Button>
                </FormControl>
                
              </form>
              </Box>
            </Modal>
          </TableCell>
        </TableRow>
      </TableBody>
    </Table>
  </TableContainer>
  }

  return (
    <ThemeProvider theme={darkTheme}>
      <CssBaseline />
      <div class="page-bg" />
      <Box sx={{ flexGrow: 1 }}>
        <AppBar position="static" style={{ background: "#127A42"}}>
          <Toolbar>
            <Typography>Plant Water Reminder</Typography>
            <div style={{ flexGrow: 1}}/>
            {user ? <AccountSignedIn auth={auth} firebase={firebase}/> : <SignIn auth={auth} firebase={firebase}/>}
          </Toolbar>
        </AppBar>
      </Box>
      <Grid container className={classes.mainLayoutGrid}>
        <Grid xs={12} className={classes.verticalMarginGrid}/>
        <Grid xs={2} className={classes.contentGrid}/>
        <Grid xs={8} className={classes.contentGrid}>
          {content}
        </Grid>
        <Grid xs={2} className={classes.contentGrid}/>
        <Grid xs={12} className={classes.verticalMarginGrid}/>
      </Grid>
    </ThemeProvider>
  );
}

export default App;
